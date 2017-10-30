package com.example.auction.transaction.impl

import java.util.UUID

import akka.Done
import com.datastax.driver.core.{PreparedStatement, Row}
import com.example.auction.transaction.api.TransactionInfoStatus.Status
import com.example.auction.transaction.api.{TransactionInfoStatus, TransactionSummary}
import com.example.auction.utils.PaginatedSequence
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor
import com.lightbend.lagom.scaladsl.persistence.cassandra.{CassandraReadSide, CassandraSession}

import scala.concurrent.{ExecutionContext, Future}

private[impl] class TransactionRepository(session: CassandraSession)(implicit ec: ExecutionContext) {

  def getTransactionsForUser(userId: UUID,
                             status: Status,
                             page: Int,
                             pageSize: Int): Future[PaginatedSequence[TransactionSummary]] = {
    val offset = page * pageSize
    val limit = (page + 1) * pageSize

    for {
      count <- countByStatus(userId, status)
      items <- if (offset > count) Future.successful(Seq.empty)
               else selectByStatus(userId, status, offset, limit)
    } yield {
      PaginatedSequence(items, page, pageSize, count)
    }
  }

  private def countByStatus(userId: UUID, status: Status) = {
    session
      .selectOne("""
        SELECT COUNT(*) FROM transactionSummaryByUserAndStatus
        WHERE userId = ? AND status = ?
        ORDER BY status ASC, itemId DESC
      """,
      userId, status.toString) map {
        case Some(row) => row.getLong("count").toInt
        case None => 0
      }
  }

  private def selectByStatus(userId: UUID, status: Status, offset: Int, limit: Int) = {
    session.selectAll("""
      SELECT * FROM transactionSummaryByUserAndStatus
      WHERE userId = ? AND status = ?
      ORDER BY status ASC, itemId DESC
      LIMIT ?
    """, userId, status.toString, Integer.valueOf(limit)) map { rows =>
      rows.drop(offset).map(convertTransactionSummary)
    }
  }

  private def convertTransactionSummary(row: Row) = {
    TransactionSummary(
      row.getUUID("itemId"),
      row.getUUID("creatorId"),
      row.getUUID("winnerId"),
      row.getString("itemTitle"),
      row.getString("currencyId"),
      row.getInt("itemPrice"),
      row.getString("status")
    )
  }
}

private[impl] class TransactionEventProcessor(session: CassandraSession,
                                              readSide: CassandraReadSide)
                                             (implicit ec: ExecutionContext)
  extends ReadSideProcessor[TransactionEvent] {

  private var insertTransactionUserStatement: PreparedStatement = _
  private var insertTransactionSummaryByUserStatement: PreparedStatement = _
  private var updateTransactionSummaryStatusStatement: PreparedStatement = _

  import TransactionInfoStatus._

  override def buildHandler() = readSide
    .builder[TransactionEvent]("transactionEventOffset")
    .setGlobalPrepare(createTables)
    .setPrepare(_ => prepareStatements())
    .setEventHandler[TransactionStarted](e => insertTransaction(e.event.itemId, e.event.transaction))
    .setEventHandler[DeliveryDetailsApproved](e => updateTransactionSummaryStatus(e.event.itemId, PaymentPending))
    .setEventHandler[PaymentDetailsSubmitted](e => updateTransactionSummaryStatus(e.event.itemId, PaymentSubmitted))
    .setEventHandler[PaymentApproved](e => updateTransactionSummaryStatus(e.event.itemId, PaymentConfirmed))
    .setEventHandler[PaymentRejected](e => updateTransactionSummaryStatus(e.event.itemId, PaymentPending))
    .build

  override def aggregateTags = TransactionEvent.Tag.allTags

  private def createTables() = {
    for {
      _ <- session.executeCreateTable("""
        CREATE TABLE IF NOT EXISTS transactionUsers (
          itemId timeuuid PRIMARY KEY,
          creatorId UUID,
          winnerId UUID
        )
      """)
      _ <- session.executeCreateTable("""
        CREATE TABLE IF NOT EXISTS transactionSummaryByUser (
          userId UUID,
          itemId timeuuid,
          creatorId UUID,
          winnerId UUID,
          itemTitle text,
          currencyId text,
          itemPrice int,
          status text,
          PRIMARY KEY (userId, itemId)
        )
        WITH CLUSTERING ORDER BY (itemId DESC)
      """)
      _ <- session.executeCreateTable("""
        CREATE MATERIALIZED VIEW IF NOT EXISTS transactionSummaryByUserAndStatus AS
        SELECT * FROM transactionSummaryByUser
        WHERE status IS NOT NULL AND itemId IS NOT NULL
        PRIMARY KEY (userId, status, itemId)
        WITH CLUSTERING ORDER BY (status ASC, itemId DESC)
      """)
    } yield Done
  }

  private def prepareStatements() = for {
    insertTransactionUser <- session.prepare("""
      INSERT INTO transactionUsers(itemId, creatorId, winnerId) VALUES (?, ?, ?)
    """)
    insertTransactionSummaryByUser <- session.prepare("""
      INSERT INTO transactionSummaryByUser(
        userId,
        itemId,
        creatorId,
        winnerId,
        itemTitle,
        currencyId,
        itemPrice,
        status
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """)
    updateTransactionSummaryStatus <- session.prepare("""
      UPDATE transactionSummaryByUser SET status = ? WHERE userId = ? AND itemId = ?
    """)
  } yield {
    insertTransactionUserStatement = insertTransactionUser
    insertTransactionSummaryByUserStatement = insertTransactionSummaryByUser
    updateTransactionSummaryStatusStatement = updateTransactionSummaryStatus
    Done
  }

  private def insertTransaction(itemId: UUID, transaction: Transaction) = {
    Future { List(
      insertTransactionUser(itemId, transaction.creator, transaction.winner),
      insertTransactionSummaryByUser(transaction.winner, itemId, transaction),
      insertTransactionSummaryByUser(transaction.creator, itemId, transaction))
    }
  }

  private def insertTransactionUser(itemId: UUID, creatorId: UUID, winnerId: UUID) = {
    insertTransactionUserStatement.bind(itemId, creatorId, winnerId)
  }

  private def insertTransactionSummaryByUser(userId: UUID, itemId: UUID, transaction: Transaction) = {
    insertTransactionSummaryByUserStatement.bind(
      userId,
      itemId,
      transaction.creator,
      transaction.winner,
      transaction.itemData.title,
      transaction.itemData.currencyId,
      Integer.valueOf(transaction.itemPrice),
      TransactionInfoStatus.NegotiatingDelivery.toString
    )
  }

  private def updateTransactionSummaryStatus(itemId: UUID, status: TransactionInfoStatus.Status) = {
    selectTransactionUser(itemId) map {
      case None => throw new IllegalStateException("No transactionUsers found for itemId " + itemId)
      case Some(row) =>
        val creatorId = row.getUUID("creatorId")
        val winnerId = row.getUUID("winnerId")
        List(
          updateTransactionSummaryStatusStatement.bind(status.toString, creatorId, itemId),
          updateTransactionSummaryStatusStatement.bind(status.toString, winnerId, itemId)
        )
    }
  }

  private def selectTransactionUser(itemId: UUID) = {
    session.selectOne("SELECT * FROM transactionUsers WHERE itemId = ?", itemId)
  }
}