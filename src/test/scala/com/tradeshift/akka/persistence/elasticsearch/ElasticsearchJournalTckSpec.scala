package com.tradeshift.akka.persistence.elasticsearch

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import com.typesafe.config.ConfigFactory
import scala.util.Random

class ElasticsearchJournalTckSpec extends JournalSpec(
  config = ConfigFactory.parseString(s"""
akka.test.timefactor = 10
akka.persistence.journal.plugin = "akka-persistence-elasticsearch-http"
akka-persistence-elasticsearch-http.indexName = "akka-journal-${Random.nextInt}"
""")) {

  override def supportsAtomicPersistAllOfSeveralEvents: Boolean = true

  override def supportsRejectingNonSerializableObjects: CapabilityFlag = false

  override def supportsSerialization: CapabilityFlag = true
}
