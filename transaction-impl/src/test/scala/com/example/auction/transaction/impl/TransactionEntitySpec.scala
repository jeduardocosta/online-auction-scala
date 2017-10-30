package com.example.auction.transaction.impl

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.example.auction.transaction.api.{PaymentInfoStatus, TransactionInfoStatus}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.InvalidCommandException
import com.lightbend.lagom.scaladsl.playjson.JsonSerializerRegistry
import com.lightbend.lagom.scaladsl.testkit.PersistentEntityTestDriver
import com.lightbend.lagom.scaladsl.testkit.PersistentEntityTestDriver.Outcome
import org.scalatest.{BeforeAndAfterAll, Matchers, OptionValues, WordSpec}

class TransactionEntitySpec extends WordSpec with Matchers
  with BeforeAndAfterAll
  with OptionValues
  with TransactionStub {

  private val system = ActorSystem("transactionEntityTest",
    JsonSerializerRegistry.actorSystemSetupFor(TransactionSerializerRegistry))

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  private def withDriver[T](block: PersistentEntityTestDriver[TransactionCommand, TransactionEvent, Option[Transaction]] => T): T = {
    val driver = new PersistentEntityTestDriver(system, new TransactionEntity, itemId.toString)
    try {
      block(driver)
    } finally {
      driver.getAllIssues shouldBe empty
    }
  }

  private def shouldContainErrorMessage[T1, T2](outcome: Outcome[T1, T2], expectedMessage: String) = {
    outcome.events shouldBe empty
    outcome.replies should have size 1
    outcome.replies.head shouldBe a [InvalidCommandException]
    outcome.replies.head.asInstanceOf[InvalidCommandException].getMessage shouldBe expectedMessage
  }

  "Transaction entity" should {
    "allow start a transaction" in withDriver { driver =>
      val outcome = driver.run(StartTransaction(transaction))
      outcome.events should contain only TransactionStarted(itemId, transaction)
      outcome.state shouldBe Some(transaction)
      outcome.state.value.status shouldBe TransactionInfoStatus.NegotiatingDelivery
    }

    "allow submitting to delivery details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val outcome = driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      outcome.events should contain only DeliveryDetailsSubmitted(itemId, deliveryInfo)
      outcome.state.value.status shouldBe TransactionInfoStatus.NegotiatingDelivery
    }

    "only allow the auction winner to submit delivery details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val nonWinner = UUID.randomUUID
      val outcome = driver.run(SubmitDeliveryDetails(userId = nonWinner, deliveryInfo))
      shouldContainErrorMessage(outcome, "Only the auction winner can submit delivery details")
    }

    "allow setting delivery price" in withDriver { driver =>
      driver.run(StartTransaction(transaction))

      val outcome = driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      outcome.events should contain only DeliveryPriceUpdated(itemId, deliveryPrice)
      outcome.state.value.deliveryPrice shouldBe Some(deliveryPrice)
      outcome.state.value.status shouldBe TransactionInfoStatus.NegotiatingDelivery
    }

    "only allow the auction creator to submit delivery price" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val nonCreator = UUID.randomUUID
      val outcome = driver.run(SetDeliveryPrice(userId = nonCreator, deliveryPrice))
      shouldContainErrorMessage(outcome, "Only the auction creator can submit delivery price")
    }

    "allow approving delivery details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))

      val outcome = driver.run(ApproveDeliveryDetails(creatorId))
      outcome.events should contain only DeliveryDetailsApproved(itemId)
      outcome.state.value.status shouldBe TransactionInfoStatus.PaymentPending
    }

    "only allow the auction creator to approving delivery details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))

      val nonCreator = UUID.randomUUID
      val outcome = driver.run(ApproveDeliveryDetails(nonCreator))

      shouldContainErrorMessage(outcome, "Only the auction creator can approve delivery details")
    }

    "not allow approve delivery details with empty delivery price" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))

      val outcome = driver.run(ApproveDeliveryDetails(creatorId))

      shouldContainErrorMessage(outcome, "The delivery info or price can not be empty")
    }

    "not allow approve delivery details with empty delivery info" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))

      val outcome = driver.run(ApproveDeliveryDetails(creatorId))

      shouldContainErrorMessage(outcome, "The delivery info or price can not be empty")
    }

    "allow submitting payment details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      driver.run(ApproveDeliveryDetails(creatorId))

      val outcome = driver.run(SubmitPaymentDetails(userId, paymentInfo))
      outcome.events should contain only PaymentDetailsSubmitted(itemId, paymentInfo)
      outcome.state.value.status shouldBe TransactionInfoStatus.PaymentSubmitted
      outcome.state.value.paymentInfo shouldBe Some(paymentInfo)
    }

    "not allow non winner submit payment details" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      driver.run(ApproveDeliveryDetails(creatorId))

      val nonWinner = UUID.randomUUID
      val outcome = driver.run(SubmitPaymentDetails(nonWinner, paymentInfo))

      shouldContainErrorMessage(outcome, "Only the auction winner can submit payment details")
    }

    "allow approving payment" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      driver.run(ApproveDeliveryDetails(creatorId))
      driver.run(SubmitPaymentDetails(userId, paymentInfo))

      val outcome = driver.run(SubmitPaymentStatus(creatorId, PaymentInfoStatus.Approved))
      outcome.events should contain only PaymentApproved(itemId)
      outcome.state.value.status shouldBe TransactionInfoStatus.PaymentConfirmed
    }

    "allow rejecting payment" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      driver.run(ApproveDeliveryDetails(creatorId))
      driver.run(SubmitPaymentDetails(userId, paymentInfo))

      val outcome = driver.run(SubmitPaymentStatus(creatorId, PaymentInfoStatus.Rejected))
      outcome.events should contain only PaymentRejected(itemId)
      outcome.state.value.status shouldBe TransactionInfoStatus.PaymentPending
    }

    "not allow non creator submit payment status" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      driver.run(SubmitDeliveryDetails(userId, deliveryInfo))
      driver.run(SetDeliveryPrice(creatorId, deliveryPrice))
      driver.run(ApproveDeliveryDetails(creatorId))
      driver.run(SubmitPaymentDetails(userId, paymentInfo))

      val nonCreator = UUID.randomUUID
      val outcome = driver.run(SubmitPaymentStatus(nonCreator, PaymentInfoStatus.Rejected))

      shouldContainErrorMessage(outcome, "Only the auction creator can submit payment status")
    }

    "allow see transaction by creator" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val outcome = driver.run(GetTransaction(creatorId))
      outcome.replies should have size 1

      val Some(obtained) = outcome.replies.head.asInstanceOf[Option[Transaction]]
      obtained.itemId shouldBe itemId
      obtained.creator shouldBe creatorId
    }

    "allow see transaction by winner" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val outcome = driver.run(GetTransaction(winnerId))
      outcome.replies should have size 1

      val Some(obtained) = outcome.replies.head.asInstanceOf[Option[Transaction]]
      obtained.itemId shouldBe itemId
      obtained.winner shouldBe winnerId
    }

    "not allow see transaction by user without permission" in withDriver { driver =>
      driver.run(StartTransaction(transaction))
      val newUser = UUID.randomUUID
      val outcome = driver.run(GetTransaction(newUser))

      val expectedMessage = "Only the item owner and the auction winner can see transaction details"

      outcome.events shouldBe empty
      outcome.replies.head shouldBe a [InvalidCommandException]
      outcome.replies.head.asInstanceOf[InvalidCommandException].getMessage shouldBe expectedMessage
    }
  }
}