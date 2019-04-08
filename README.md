# Akka persistence elasticsearch journal

This project implements a journal plugin for [Akka
persistence](https://doc.akka.io/docs/akka/current/persistence.html). It was inspired by earlier
implementations like the one from [nilsga](https://github.com/nilsga/akka-persistence-elasticsearch), but
differentiates by using HTTP as transport layer. This hopefully makes upgrades to other ElasticSearch versions
a little easier, by not having any binary bindings to ES libraries.

Its current implementation is tested using the following stack:

- Scala 2.12.7
- Akka 2.5.21
- Elasticsearch 6.5.3

## Usage

Add the current release as a dependency to your `build.sbt`:

```scala
libraryDependencies += "com.tradeshift" %% "akka-persistence-elasticsearch-http" % "0.1.1"
```

And configure `application.conf` to use the journal:

```
akka.persistence.journal.plugin = "akka-persistence-elasticsearch-http"

akka-persistence-elasticsearch-http {
  # The hostname to contact ElasticSearch on (over HTTP)
  host = "localhost"

  # The port to contact ElasticSearch on (over HTTP)
  port = 9200

  # The index name to create and/or use on ES
  indexName = "akka-journal"
}
```

## Why would I use a search engine as a journal?

I'll take the liberty of quoting `nilsga` here:

> You probably wouldn't, unless you already have ES as a part of your infrastructure, and don't want to introduce yet another component.
