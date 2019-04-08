val akkaVersion = "2.5.21"

version := "0.0.1-SNAPSHOT"

organization := "com.tradeshift"

scalaVersion := "2.12.7"

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

fork := true

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.1.8",
  "org.json4s" %% "json4s-native" % "3.6.3",
  "com.typesafe.akka" %% "akka-persistence-tck" % akkaVersion % "test",
  "org.slf4j" % "slf4j-log4j12" % "1.7.26" % "test"
)
