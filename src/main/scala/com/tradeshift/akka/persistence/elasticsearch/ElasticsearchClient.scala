package com.tradeshift.akka.persistence.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpEntity, HttpMethod, HttpMethods }
import akka.http.scaladsl.model.HttpMethods.{PUT, POST}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, RequestEntity, Uri }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.Uri.Path./
import com.typesafe.config.Config
import org.json4s.JsonAST.{ JDouble, JInt, JLong, JObject, JValue }
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{parse, render, pretty}
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.http.scaladsl.unmarshalling.Unmarshal

class ElasticsearchClient(implicit system: ActorSystem) {
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val config = system.settings.config.getConfig("akka-persistence-elasticsearch-http")
  val host = config.getString("host")
  val port = config.getString("port")
  val baseUri = Uri(s"http://${host}:${port}")
  val indexName = config.getString("indexName")
  val http = Http()

  def createIndex(settings: JObject, mappings: JObject): Future[Unit] = {
    for {
      resp1 <- request(
        method = PUT,
        path = /(indexName),
        entity = HttpEntity(`application/json`, pretty(render(
          ("settings" -> settings) ~
          ("mappings" -> ("_doc" -> mappings))
        )))
      ).recover {
        case x if x.getMessage.contains("resource_already_exists_exception") =>
          // Ignore, assuming we've already created with the right settings.
          HttpResponse()
      }
      resp2 <- request(
        method = PUT,
        path = /(indexName) / "_mapping" / "_doc",
        entity = HttpEntity(`application/json`, pretty(render(mappings))))
    } yield {
      println("Created.")
    }
  }

  def index(id: String, doc: JObject): Future[Unit] = {
    println("index " + id + ": " + pretty(render(doc)))
    request(
      path = /(indexName) / "_doc" / id,
      entity = HttpEntity(`application/json`, pretty(render(doc))))
    .map(resp => ())
  }

  // https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-max-aggregation.html
  // ES aggregates as double always. Should be fine until someone hits 2^53 sequence numbers.
  def searchMax(q: JObject, field: String): Future[Option[Double]] = {
    println("search max " + q + " for " + field)
    search(q ~ ("aggs" -> ("max_value" -> ("max" -> ("field" -> field)))), size = 0).map { resp =>
      (resp \\ "aggregations" \\ "value" \\ classOf[JDouble]).headOption
    }.map { o =>
      println("   was " + o)
      o
    }
  }

  def search(q: JObject, size: Int = 10000): Future[JValue] = {
    println("search " + pretty(render(q)))
    request(
      path = /(indexName) / "_search",
      query = Query(("size", size.toString)),
      entity = HttpEntity(`application/json`, pretty(render(q))))
      .flatMap(resp => Unmarshal(resp).to[String])
      .map(resp => {
        parse(resp)
      })
  }

  def deleteAll(q: JObject): Future[Unit] = {
    println("deleteAll " + pretty(render(q)))
    request(
      path = /(indexName) / "_delete_by_query",
      entity = HttpEntity(`application/json`, pretty(render(q))))
      .map(resp => ())
  }

  private def request(path: Path, entity: RequestEntity, query: Query = Query.Empty, method: HttpMethod = POST): Future[HttpResponse] = {
    http.singleRequest(HttpRequest(uri = baseUri.withPath(path).withQuery(query), entity = entity, method = method)).map { resp =>
      if (resp.status.isFailure()) {
        resp.discardEntityBytes()
        throw new RuntimeException("ES request failed: " + resp)
      }
      resp
    }
  }
}
