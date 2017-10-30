package com.example.auction.transaction.impl

import java.time.Duration
import java.util.UUID

import com.example.auction.item.api
import com.example.auction.item.api._
import com.example.auction.security.ClientSecurity.authenticate
import com.example.auction.transaction.api._
import com.lightbend.lagom.scaladsl.api.AdditionalConfiguration
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LocalServiceLocator}
import com.lightbend.lagom.scaladsl.testkit.{ProducerStubFactory, ServiceTest, TestTopicComponents}
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, Matchers}
import play.api.Configuration
import play.api.libs.ws.ahc.AhcWSComponents

class TransactionServiceSpec extends AsyncWordSpec with Matchers
  with BeforeAndAfterAll
  with TransactionStub {

  private val server = ServiceTest.startServer(ServiceTest.defaultSetup.withCassandra(true)) { ctx =>
    new LagomApplication(ctx)
      with TransactionComponents
      with LocalServiceLocator
      with AhcWSComponents
      with TestTopicComponents {
      override def additionalConfiguration: AdditionalConfiguration =
        super.additionalConfiguration ++ Configuration.from(Map(
          "cassandra-query-journal.eventual-consistency-delay" -> "0"
        ))
    }
  }

  override def afterAll: Unit = server.stop()

  import server.materializer

  val transactionService = server.serviceClient.implement[TransactionService]

  private val stubFactory = new ProducerStubFactory(server.actorSystem, server.materializer)
  private val itemStub = stubFactory.producer[ItemEvent]("item-ItemEvent")

  private def buildItem(creatorId: UUID, winner: Option[UUID] = None) = {
    api.Item
      .create(creatorId, "title", "description", "USD", 10, 10, Duration.ofMinutes(10))
      .copy(auctionWinner = winner)
  }

  private def createValidItem(): Unit = {
    val item = buildItem(creatorId, winner = Some(winnerId))
    itemStub.send(AuctionFinished(itemId, item))
  }

  private def retrieveTransaction(itemId: UUID, creatorId: UUID) = {
    transactionService
      .getTransaction(itemId)
      .handleRequestHeader(authenticate(creatorId))
      .invoke
  }

  private def submitDeliveryDetails(itemId: UUID, winnerId: UUID, deliveryInfo: DeliveryInfo) = {
    transactionService
      .submitDeliveryDetails(itemId)
      .handleRequestHeader(authenticate(winnerId))
      .invoke(deliveryInfo)
  }

  private def setDeliveryPrice(itemId: UUID, creatorId: UUID, deliveryPrice: Int) = {
    transactionService
      .setDeliveryPrice(itemId)
      .handleRequestHeader(authenticate(creatorId))
      .invoke(deliveryPrice)
  }

  private def approveDeliveryDetails(itemId: UUID, creatorId: UUID) = {
    transactionService
      .approveDeliveryDetails(itemId)
      .handleRequestHeader(authenticate(creatorId))
      .invoke
  }

  private def submitPaymentDetails(itemId: UUID, winnerId: UUID, paymentInfo: PaymentInfo) = {
    transactionService
      .submitPaymentDetails(itemId)
      .handleRequestHeader(authenticate(winnerId))
      .invoke(paymentInfo)
  }

  private def approvePayment(itemId: UUID, creatorId: UUID) = {
    setPaymentStatus(itemId, creatorId, PaymentInfoStatus.Approved)
  }

  private def rejectPayment(itemId: UUID, creatorId: UUID) = {
    setPaymentStatus(itemId, creatorId, PaymentInfoStatus.Rejected)
  }

  private def setPaymentStatus(itemId: UUID, creatorId: UUID, status: PaymentInfoStatus.Status) = {
    transactionService
      .submitPaymentStatus(itemId)
      .handleRequestHeader(authenticate(creatorId))
      .invoke(status)
  }

  "The transaction service" should {
    "create a transaction" in {
      createValidItem()

      for {
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.NegotiatingDelivery
      }
    }

    "not create a transaction when item has not winner" in {
      val item = buildItem(creatorId, winner = None)
      itemStub.send(AuctionFinished(itemId, item))

      for {
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.NegotiatingDelivery
      }
    }

    "submit delivery details" in {
      createValidItem()
      val newDeliveryInfo = deliveryInfo.copy(addressLine1 = "myAddressLine1", postalCode = 999)

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, newDeliveryInfo)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.deliveryInfo shouldBe newDeliveryInfo
      }
    }

    "set delivery price" in {
      createValidItem()

      for {
       _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
       transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.deliveryPrice shouldBe Some(25)
      }
    }

    "approve delivery details" in {
      createValidItem()

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, deliveryInfo)
        _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
        _           <- approveDeliveryDetails(itemId, creatorId)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.PaymentPending
      }
    }

    "not approve delivery details when delivery price is empty" in {
      createValidItem()

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, deliveryInfo)
        _           <- approveDeliveryDetails(itemId, creatorId)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.PaymentPending
      }
    }

    "not approve delivery details when delivery details is empty" in {
      createValidItem()

      for {
        _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
        _           <- approveDeliveryDetails(itemId, creatorId)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.PaymentPending
      }
    }

    "submit payment details" in {
      val newPaymentInfo = paymentInfo.copy("submitted")

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, deliveryInfo)
        _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
        _           <- approveDeliveryDetails(itemId, creatorId)
        _           <- submitPaymentDetails(itemId, winnerId, newPaymentInfo)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.paymentInfo shouldBe Some(newPaymentInfo)
        transaction.status shouldBe TransactionInfoStatus.PaymentSubmitted
      }
    }

    "approve payment" in {
      val newPaymentInfo = paymentInfo.copy("approved")

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, deliveryInfo)
        _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
        _           <- approveDeliveryDetails(itemId, creatorId)
        _           <- submitPaymentDetails(itemId, winnerId, newPaymentInfo)
        _           <- approvePayment(itemId, creatorId)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.PaymentConfirmed
      }
    }

    "reject payment" in {
      val newPaymentInfo = paymentInfo.copy("rejected")

      for {
        _           <- submitDeliveryDetails(itemId, winnerId, deliveryInfo)
        _           <- setDeliveryPrice(itemId, creatorId, deliveryPrice)
        _           <- approveDeliveryDetails(itemId, creatorId)
        _           <- submitPaymentDetails(itemId, winnerId, newPaymentInfo)
        _           <- rejectPayment(itemId, creatorId)
        transaction <- retrieveTransaction(itemId, creatorId)
      } yield {
        transaction.itemId shouldBe itemId
        transaction.status shouldBe TransactionInfoStatus.PaymentPending
      }
    }
  }
}