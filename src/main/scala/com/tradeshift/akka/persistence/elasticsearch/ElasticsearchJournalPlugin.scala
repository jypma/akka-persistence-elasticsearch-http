
package com.tradeshift.akka.persistence.elasticsearch

import akka.actor.{ Actor, Timers }
import akka.persistence.{ AtomicWrite, PersistentRepr }
import akka.persistence.journal.{ AsyncRecovery, AsyncWriteJournal }
import akka.serialization.SerializationExtension
import java.util.Base64
import org.json4s.JsonAST.{ JInt, JLong, JObject, JString, JValue }
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Success, Try }
import scala.util.control.NonFatal
import org.json4s.JsonDSL._
import scala.concurrent.duration._

class ElasticsearchJournalPlugin extends AsyncWriteJournal with AsyncRecovery with Timers {
  import ElasticsearchJournalPlugin._
  import context.dispatcher

  val serializer = SerializationExtension(context.system)
  val client = new ElasticsearchClient()(context.system)
  val replayChunkSize = context.system.settings.config.getInt("akka-persistence-elasticsearch-http.recovery-chunk-size")

  val indexSettings: JObject = ("index.codec" -> "best_compression")
  val indexMappings: JObject = ("dynamic" -> "false") ~ ("properties" ->
    ("persistenceId" -> ("type" -> "keyword")) ~
    ("sequenceNr" -> ("type" -> "long")) ~
    ("message" -> ("type" -> "binary"))
  )
  val init = client.createIndex(indexSettings, indexMappings)

  val indexDelay = 2.seconds
  val knownHighest = mutable.Map.empty[String, Promise[Long]]
  val knownDirty = mutable.Map.empty[String, Promise[Unit]]

  def afterInit[T](block: => Future[T]) = init.flatMap(_ => block)
  def afterNonDirty[T](persistenceId: String)(block: => Future[T]) = {
    val wait: Future[Unit] = knownDirty.get(persistenceId).map(_.future).getOrElse(Future.successful(()))
    wait.flatMap(_ => block)
  }

  // FIXME keep track of writes in progress, so we don't return a highest sequence nr until the write is done
  // (and ES has indexed it !!!)
  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = afterInit {
    Future.sequence(messages.map(write => try {
      if (write.payload.size > 1) {
        for (i <- 1 until write.payload.size) {
          assume(write.payload(i).sequenceNr == write.payload(0).sequenceNr + i)
        }
      }
      val doc: JObject =
        ("persistenceId" -> write.payload.head.persistenceId) ~
        ("sequenceNr" -> write.payload.map(_.sequenceNr)) ~
        ("message" -> write.payload.map { pr =>
          Base64.getEncoder().encodeToString(serializer.serialize(pr).get)
        })
      timers.startSingleTimer(write.persistenceId, ForgetHighest(write.persistenceId), indexDelay)
      if (knownHighest.get(write.persistenceId).exists(p => !p.isCompleted)) {
        // We may be emitting too low sequence numbers on quick restart in this case.
        println("WARN: concurrent write detected for " + write.persistenceId)
      }
      println("   go write, existing = " + knownDirty.get(write.persistenceId))
      if (knownDirty.get(write.persistenceId).filter(_.isCompleted).isEmpty) {
        knownDirty(write.persistenceId) = Promise[Unit]
      }
      val p1 = Promise[Long]
      knownHighest(write.persistenceId) = p1
      client.index(s"${write.payload.head.persistenceId}-${write.payload.head.sequenceNr}", doc)
        .transform(t => {
          println("  Completing " + write.persistenceId)
          p1.success(if (t.isSuccess) write.payload.last.sequenceNr else write.payload.head.sequenceNr - 1)
          Success(t)
        })
    } catch {
      case NonFatal(ex) =>
        knownHighest -= write.persistenceId
        knownDirty -= write.persistenceId
        Future.failed(ex)
    }))
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = afterInit {
    println("delete " + persistenceId + " to " + toSequenceNr)
    val f = afterNonDirty(persistenceId) {
      println("   go, existing = " + knownDirty.get(persistenceId))
      if (knownDirty.get(persistenceId).filter(_.isCompleted).isEmpty) {
        knownDirty(persistenceId) = Promise[Unit]
      }
      timers.startSingleTimer(persistenceId, ForgetHighest(persistenceId), indexDelay)
      deletePartial(persistenceId, toSequenceNr).flatMap { max => deleteDocs(persistenceId, max)}
    }
    f.onComplete(r => println("delete complete: " + r))

    f
  }

  private def deletePartial(persistenceId: String, to: Long): Future[Long] = {
    println("deletePartial " + persistenceId + " to " + to)
    val matchPersistenceId: JObject = ("term" -> ("persistenceId" -> persistenceId))
    val matchSeqNr: JObject = ("term" -> ("sequenceNr" -> to))
    client.search("query" -> ("bool" -> ("must" -> Seq(matchPersistenceId, matchSeqNr)))).flatMap { resp =>
      val hits = resp \ "hits" \ "hits"
      val sequenceNrs = for { JLong(nr) <- hits \\ "sequenceNr" } yield nr
      if (!(sequenceNrs.contains(to)) || (sequenceNrs.size == 1) || sequenceNrs.lastOption.exists(_ == to)) {
        // No need for a partial update, we can delete this doc fully with the rest
        Future.successful(to)
      } else {
        val docId = (for (JString(id) <- hits \\ "_id") yield id).head
        val messages = sequenceNrs
          .zip(for (JString(message) <- hits \\ "message") yield message)
          .drop(sequenceNrs.indexWhere(_ == to)) // drop all messages <= to
        client.index(docId,
          ("persistenceId" -> persistenceId) ~
            ("sequenceNr" -> messages.map(_._1)) ~
            ("message" -> messages.map(_._2))).map { _ =>
          // Delete all full documents up to here
          sequenceNrs.head - 1
        }
      }
    }
  }

  private def deleteDocs(persistenceId: String, to: Long): Future[Unit] = {
    println("deleteDocs " + persistenceId + " to " + to)
    val matchPersistenceId: JObject = ("term" -> ("persistenceId" -> persistenceId))
    val matchSeqNr: JObject = ("range" -> ("sequenceNr" -> ("lte" -> to)))
    client.deleteAll("query" -> ("bool" -> ("must" -> Seq(matchPersistenceId, matchSeqNr))))
  }

  override def receivePluginInternal: Actor.Receive = {
    case ForgetHighest(persistenceId) =>
      for (p <- knownHighest.get(persistenceId)) {
        if (p.isCompleted) {
          knownHighest -= persistenceId
        } else {
          // Write must still be in progress, try again later
          println("retry later forgetting " + persistenceId)
          timers.startSingleTimer(persistenceId, ForgetHighest(persistenceId), 10.seconds)
        }
      }

      for (d <- knownDirty.get(persistenceId)) {
        d.success(())
      }
      knownDirty -= persistenceId
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long,
    max: Long)(recoveryCallback: PersistentRepr ⇒ Unit): Future[Unit] = afterInit {
    println("asyncReplay " + persistenceId + " " + fromSequenceNr + ".." + toSequenceNr)

    val to = if ((toSequenceNr - fromSequenceNr) < max) toSequenceNr else fromSequenceNr + max - 1

    afterNonDirty(persistenceId) {
      println("   go")
      replay(persistenceId, fromSequenceNr, to)(recoveryCallback)
    }
  }

  private def replay(persistenceId: String, from: Long, to: Long)(recoveryCallback: PersistentRepr ⇒ Unit): Future[Unit] = {
    if (to - from > replayChunkSize) {
      replay(persistenceId, from, from + replayChunkSize)(recoveryCallback).flatMap { done =>
        replay(persistenceId, from + replayChunkSize + 1, to)(recoveryCallback)
      }
    } else {
      val matchPersistenceId: JObject = ("term" -> ("persistenceId" -> persistenceId))
      val matchSeqNr: JObject = ("range" -> ("sequenceNr" -> ("gte" -> from) ~ ("lte" -> to)))
      client.search(("query" -> ("bool" -> ("must" -> Seq(matchPersistenceId, matchSeqNr)))) ~ ("sort" -> Seq("sequenceNr"))).map { resp =>
        val hits = resp \ "hits" \ "hits"

        println(hits)
        val sequenceNrs = for { JInt(nr) <- hits \\ "sequenceNr" } yield nr
        // if (sequenceNrs != (from.to(to))) {
        //   this actually happens when msgs are deleted now.
        //   throw new RuntimeException("Received invalid seq nrs for " + persistenceId + ". Expected " + from + ".." + to + ", got " + sequenceNrs)
        // }
        for (JString(message) <- hits \\ "message") {
          val pr = serializer.deserialize[PersistentRepr](Base64.getDecoder().decode(message), classOf[PersistentRepr]).get
          recoveryCallback(pr)
        }
      }
    }
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = afterInit {
    knownHighest.get(persistenceId) match {
      case Some(p) => p.future
      case None => // no write in progress or recently completed
        val term: JObject = ("term" -> ("persistenceId" -> persistenceId))
        val range: JObject = ("range" -> ("sequenceNr" -> ("gte" -> fromSequenceNr)))

        client.searchMax(("query" -> ("bool" -> ("must" -> Seq(term, range)))), "sequenceNr").map(_.map(_.toLong).getOrElse(0))
    }
  }

}

object ElasticsearchJournalPlugin {
  private[elasticsearch] case class ForgetHighest(persistenceId: String)
}
