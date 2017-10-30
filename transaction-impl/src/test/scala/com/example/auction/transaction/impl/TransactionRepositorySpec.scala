package com.example.auction.transaction.impl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.persistence.query.Sequence
import com.datastax.driver.core.utils.UUIDs
import com.example.auction.transaction.api.{TransactionInfoStatus, TransactionSummary}
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.lightbend.lagom.scaladsl.testkit.ServiceTest
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, Matchers}
import play.api.libs.ws.ahc.AhcWSComponents

import scala.concurrent.Future

class TransactionRepositorySpec extends AsyncWordSpec with Matchers
  with BeforeAndAfterAll
  with TransactionStub {

  private val server = ServiceTest.startServer(ServiceTest.defaultSetup.withCassandra(true)) { ctx =>
    new LagomApplication(ctx) with TransactionComponents with AhcWSComponents with LagomKafkaComponents {
      override def serviceLocator = NoServiceLocator
      override lazy val readSide: ReadSideTestDriver = new ReadSideTestDriver
    }
  }

  override def afterAll(): Unit = server.stop()

  private val testDriver = server.application.readSide
  private val repository = server.application.transactionRepository
  private val offset = new AtomicInteger()

  private def feed(itemId: UUID, transaction: TransactionEvent) = {
    testDriver.feed(itemId.toString, transaction, Sequence(offset.getAndIncrement))
  }

  private def getTransactions(userId: UUID,
                              status: TransactionInfoStatus.Status,
                              page: Int = 0,
                              pageSize: Int = 10) = {
    repository.getTransactionsForUser(userId, status, page = 0, pageSize = pageSize)
  }

  private def buildTransactionSummary(transaction: Transaction, status: TransactionInfoStatus.Status) = {
    TransactionSummary(itemId, creatorId, winnerId,
      transaction.itemData.title, transaction.itemData.currencyId,
      transaction.itemPrice, status.toString)
  }

  private def getStartedTransactionByUserId(userId: UUID) = {
    val newTransaction = transaction

    for {
      _ <- feed(itemId, TransactionStarted(itemId, newTransaction))
      transactions <- getTransactions(userId, TransactionInfoStatus.NegotiatingDelivery)
    } yield {
      transactions.items should contain only
        buildTransactionSummary(newTransaction, TransactionInfoStatus.NegotiatingDelivery)
    }
  }

  "The transaction processor" should {
    "get started transaction by creator id" in {
      getStartedTransactionByUserId(creatorId)
    }

    "get started transaction by winner id" in {
      getStartedTransactionByUserId(winnerId)
    }

    "update to payment pending status" in {
      val newTransaction = transaction

      for {
        _ <- feed(itemId, TransactionStarted(itemId, newTransaction))
        _ <- feed(itemId, DeliveryDetailsSubmitted(itemId, deliveryInfo))
        _ <- feed(itemId, DeliveryPriceUpdated(itemId, deliveryPrice))
        _ <- feed(itemId, DeliveryDetailsApproved(itemId))
        transactions <- getTransactions(creatorId, TransactionInfoStatus.PaymentPending)
      } yield {
        transactions.items should contain only
          buildTransactionSummary(newTransaction, TransactionInfoStatus.PaymentPending)
      }
    }

    "update to payment submitted status" in {
      val newTransaction = transaction

      for {
        _ <- feed(itemId, TransactionStarted(itemId, newTransaction))
        _ <- feed(itemId, DeliveryDetailsSubmitted(itemId, deliveryInfo))
        _ <- feed(itemId, DeliveryPriceUpdated(itemId, deliveryPrice))
        _ <- feed(itemId, DeliveryDetailsApproved(itemId))
        _ <- feed(itemId, PaymentDetailsSubmitted(itemId, paymentInfo))
        transactions <- getTransactions(creatorId, TransactionInfoStatus.PaymentSubmitted)
      } yield {
        transactions.items should contain only
          buildTransactionSummary(newTransaction, TransactionInfoStatus.PaymentSubmitted)
      }
    }

    "update to payment confirmed status" in {
      val newTransaction = transaction

      for {
        _ <- feed(itemId, TransactionStarted(itemId, newTransaction))
        _ <- feed(itemId, DeliveryDetailsSubmitted(itemId, deliveryInfo))
        _ <- feed(itemId, DeliveryPriceUpdated(itemId, deliveryPrice))
        _ <- feed(itemId, DeliveryDetailsApproved(itemId))
        _ <- feed(itemId, PaymentDetailsSubmitted(itemId, paymentInfo))
        _ <- feed(itemId, PaymentApproved(itemId))
        transactions <- getTransactions(creatorId, TransactionInfoStatus.PaymentConfirmed)
      } yield {
        transactions.items should contain only
          buildTransactionSummary(newTransaction, TransactionInfoStatus.PaymentConfirmed)
      }
    }

    "paginate obtained transactions" in {
      for {
        _ <- Future.sequence((1 to 30).map(_ => {
          val newItemId = UUIDs.timeBased
          val newTransaction = transaction.copy(itemId = newItemId)
          feed(newItemId, TransactionStarted(newItemId, newTransaction))
        }))
        transactions <- getTransactions(creatorId, TransactionInfoStatus.NegotiatingDelivery)
      } yield {
        transactions.count shouldBe 30
        transactions.page shouldBe 0
        transactions.pageSize shouldBe 10
        transactions.items should have size 10
      }
    }

    "limit to 5 records" in {
      for {
        _ <- Future.sequence((1 to 10).map(_ => {
          val newItemId = UUIDs.timeBased
          val newTransaction = transaction.copy(itemId = newItemId)
          feed(newItemId, TransactionStarted(newItemId, newTransaction))
        }))
        transactions <- getTransactions(creatorId, TransactionInfoStatus.NegotiatingDelivery, pageSize = 5)
      } yield {
        transactions.page shouldBe 0
        transactions.pageSize shouldBe 5
        transactions.items should have size 5
      }
    }
  }
}