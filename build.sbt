val akkaVersion = "2.5.21"

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

addCompilerPlugin(scalafixSemanticdb) // Required by ScalaFix
 scalacOptions ++= Seq(
    "-feature",
    "-Xlint",
    "-Yrangepos",          // required by SemanticDB
    "-Ywarn-unused-import" // required by RemoveUnused
)

import sbtrelease._
// we hide the existing definition for setReleaseVersion to replace it with our own
import sbtrelease.ReleaseStateTransformations.{setReleaseVersion=>_,_}

def setVersionOnly(selectVersion: Versions => String): ReleaseStep =  { st: State =>
  val vs = st.get(ReleaseKeys.versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
  val selected = selectVersion(vs)

  st.log.info("Setting version to '%s'." format selected)
  val useGlobal =Project.extract(st).get(releaseUseGlobalVersion)
  val versionStr = (if (useGlobal) globalVersionString else versionString) format selected

  reapply(Seq(
    if (useGlobal) version in ThisBuild := selected
    else version := selected
  ), st)
}

lazy val setReleaseVersion: ReleaseStep = setVersionOnly(_._1)

releaseVersionBump := { System.getProperty("BUMP", "default").toLowerCase match {
  case "major" => sbtrelease.Version.Bump.Major
  case "minor" => sbtrelease.Version.Bump.Minor
  case "bugfix" => sbtrelease.Version.Bump.Bugfix
  case "default" => sbtrelease.Version.Bump.default
}}

releaseVersion := { ver => Version(ver)
  .map(_.withoutQualifier)
  .map(_.bump(releaseVersionBump.value).string).getOrElse(versionFormatError)
}

val VersionRegex = "v([0-9]+.[0-9]+.[0-9]+)-?(.*)?".r

releaseProcess := Seq(
  checkSnapshotDependencies,
  inquireVersions,
  setReleaseVersion,
  runClean,
  runTest,
  tagRelease,
  publishArtifacts,
  pushChanges
)
