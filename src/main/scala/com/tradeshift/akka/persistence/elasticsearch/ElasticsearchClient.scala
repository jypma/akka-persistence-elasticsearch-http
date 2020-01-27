package com.tradeshift.akka.persistence.elasticsearch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpEntity, HttpMethod }
import akka.http.scaladsl.model.HttpMethods.{GET, PUT, POST}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, RequestEntity, Uri, StatusCodes }
import StatusCodes.NotFound
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.Uri.Path./
import org.json4s.JsonAST.{ JDouble, JObject, JValue }
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{parse, render, pretty}
import scala.concurrent.Future
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.scalalogging.StrictLogging
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.BasicHttpCredentials

class ElasticsearchClient(implicit system: ActorSystem) extends StrictLogging {
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val config = system.settings.config.getConfig("akka-persistence-elasticsearch-http")
  val host = config.getString("host")
  val port = config.getString("port")
  val defaultHeaders: Vector[HttpHeader] = {
    val username = config.getString("username")
    val password = config.getString("password")
    if (!username.isEmpty || !password.isEmpty) {
      Vector(Authorization(BasicHttpCredentials(username, password)))
    } else {
      Vector.empty
    }
  }
  val baseUri = Uri(s"http://${host}:${port}")
  val indexName = config.getString("indexName")
  val http = Http()

  def createIndex(settings: JObject, mappings: JObject): Future[Unit] = {
    for {
      resp1 <- request(PUT,
        path = /(indexName),
        query = Query("include_type_name" -> "true"),  // needed from ES 6.7+
        entity = HttpEntity(`application/json`, pretty(render(
          ("settings" -> settings) ~
          ("mappings" -> ("_doc" -> mappings))
        )))
      ).map{
        r => r.discardEntityBytes()
        r
      }.recover {
        case x if x.getMessage.contains("resource_already_exists_exception") =>
          // Ignore, assuming we've already created with the right settings.
          HttpResponse()
      }
      _ <- resp1.discardEntityBytes().future
      resp2 <- request(
        method = PUT,
        path = /(indexName) / "_mapping" / "_doc",
        entity = HttpEntity(`application/json`, pretty(render(mappings))))
      _ <- resp2.discardEntityBytes().future
    } yield {
      logger.debug(s"Created or updated elasticsearch journal index ${indexName}.")
    }
  }

  def index(id: String, doc: JObject): Future[Unit] = {
    logger.debug(s"Store ${id}: ${pretty(render(doc))}")
    request(POST,
      path = /(indexName) / "_doc" / id,
      entity = HttpEntity(`application/json`, pretty(render(doc))))
      .flatMap(resp => resp.discardEntityBytes().future)
      .map{d =>
        logger.debug(s"Stored: ${id}")
        ()
      }
  }

  def upsert(id: String, fields: JObject): Future[Unit] = {
    logger.debug(s"Upsert ${id}: ${pretty(render(fields))}")
    request(POST,
      path = /(indexName) / "_doc" / id / "_update",
      entity = HttpEntity(`application/json`, pretty(render(
        ("doc" -> fields) ~
        ("doc_as_upsert" -> true)
      ))))
      .flatMap(resp => resp.discardEntityBytes().future)
      .map(_ => ())
  }

  def get(id: String): Future[Option[JValue]] = {
    logger.debug(s"get ${id}")
    request(GET,
      path = /(indexName) / "_doc" / id)
      .flatMap(resp => Unmarshal(resp).to[String])
      .map(resp => Some(parse(resp) \\ "_source"))
      .recover {
        case x:NoSuchElementException => None
      }
  }

  // https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-max-aggregation.html
  // ES aggregates as double always. Should be fine until someone hits 2^53 sequence numbers.
  def searchMax(q: JObject, field: String): Future[Option[Double]] = {
    logger.debug(s"Search max $q for $field")
    search(q ~ ("aggs" -> ("max_value" -> ("max" -> ("field" -> field)))), size = 0).map { resp =>
      (resp \\ "aggregations" \\ "value" \\ classOf[JDouble]).headOption
    }
  }

  def search(q: JObject, size: Int = 10000): Future[JValue] = {
    logger.debug(s"search ${pretty(render(q))}")
    request(POST,
      path = /(indexName) / "_search",
      query = Query(("size", size.toString)),
      entity = HttpEntity(`application/json`, pretty(render(q))))
      .flatMap(resp => Unmarshal(resp).to[String])
      .map(resp => parse(resp))
  }

  def deleteAll(q: JObject): Future[Unit] = {
    logger.debug(s"deleteAll ${pretty(render(q))}")
    request(POST,
      path = /(indexName) / "_delete_by_query",
      query = Query("conflicts" -> "proceed"),
      entity = HttpEntity(`application/json`, pretty(render(q))))
      .flatMap(resp => Unmarshal(resp).to[String])
      .map(resp => parse(resp))
      .map { json =>
        for (v <- json \\ "version_conflicts" \\ classOf[JDouble]) {
          logger.warn(s"Version conflicts: ${v}")
        }
      }
  }

  private def request(method: HttpMethod, path: Path, entity: RequestEntity = HttpEntity.Empty, query: Query = Query.Empty): Future[HttpResponse] = {

    http.singleRequest(HttpRequest(
      uri = baseUri.withPath(path).withQuery(query),
      entity = entity,
      method = method,
      headers = defaultHeaders
    )).map { resp =>
      if (resp.status.isFailure()) {
        resp.discardEntityBytes()
        if (resp.status == NotFound) {
          throw new NoSuchElementException("Not found: " + path)
        } else {
          throw new RuntimeException("ES request failed: " + resp)
        }
      }
      resp
    }
  }
}
