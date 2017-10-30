package com.example.auction.transaction.impl

import com.example.auction.item.api.ItemService
import com.example.auction.transaction.api.TransactionService
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServerComponents}
import com.typesafe.conductr.bundlelib.lagom.scaladsl.ConductRApplicationComponents
import play.api.libs.ws.ahc.AhcWSComponents
import com.softwaremill.macwire._
import play.api.Environment

import scala.concurrent.ExecutionContext

trait TransactionComponents extends LagomServerComponents
   with CassandraPersistenceComponents {

  implicit def executionContext: ExecutionContext
  def environment: Environment

  override lazy val lagomServer = serverFor[TransactionService](wire[TransactionServiceImpl])
  lazy val transactionRepository = wire[TransactionRepository]
  lazy val jsonSerializerRegistry = TransactionSerializerRegistry

  persistentEntityRegistry.register(wire[TransactionEntity])
  readSide.register(wire[TransactionEventProcessor])
}

abstract class TransactionApplication(context: LagomApplicationContext) extends LagomApplication(context)
  with TransactionComponents
  with AhcWSComponents
  with LagomKafkaComponents {

  lazy val itemService = serviceClient.implement[ItemService]
  wire[TransactionServiceSubscriber]
}

class TransactionApplicationLoader extends LagomApplicationLoader {
  override def load(context: LagomApplicationContext) =
    new TransactionApplication(context) with ConductRApplicationComponents

  override def loadDevMode(context: LagomApplicationContext) =
    new TransactionApplication(context) with LagomDevModeComponents

  override def describeService = Some(readDescriptor[TransactionService])
}