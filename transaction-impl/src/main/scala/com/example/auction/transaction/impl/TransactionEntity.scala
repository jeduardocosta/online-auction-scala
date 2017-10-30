package com.example.auction.transaction.impl

import java.util.UUID

import akka.Done
import com.example.auction.item.api.ItemSummary
import com.example.auction.transaction.api._
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventShards, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import play.api.libs.json.{Format, Json}

class TransactionEntity extends PersistentEntity {

  import TransactionInfoStatus._

  override type Command = TransactionCommand
  override type Event = TransactionEvent
  override type State = Option[Transaction]

  override def initialState: Option[Transaction] = None

  override def behavior: Behavior = {
    case None => notStarted
    case Some(transaction) if transaction.status == NegotiatingDelivery => negotiatingDelivery(transaction)
    case Some(transaction) if transaction.status == PaymentPending => paymentPending(transaction)
    case Some(transaction) if transaction.status == PaymentSubmitted => paymentSubmitted(transaction)
    case Some(transaction) if transaction.status == PaymentConfirmed => paymentConfirmed(transaction)
  }

  private def getTransactionCommand(transaction: Transaction) = {
    Actions()
      .onReadOnlyCommand[GetTransaction, Option[Transaction]] {
      case (GetTransaction(userId), ctx, state) if transaction.creator == userId || transaction.winner == userId =>
        ctx.reply(state)
      case (_, ctx, _) =>
        ctx.invalidCommand("Only the item owner and the auction winner can see transaction details")
        ctx.reply(None)
    }
  }

  private val notStarted = {
    Actions()
      .onCommand[StartTransaction, Done] {
        case (StartTransaction(transaction), ctx, _) =>
          ctx.thenPersist(TransactionStarted(transaction.itemId, transaction))(_ => ctx.reply(Done))
      }
      .onEvent {
        case (TransactionStarted(_, transaction), _) => Some(transaction.updateStatus(NegotiatingDelivery))
      }
  }

  private def negotiatingDelivery(transaction: Transaction) = {
    Actions()
      .onReadOnlyCommand[StartTransaction, Done] {
        case (_, ctx, _) => ctx.reply(Done)
      }
      .onCommand[SubmitDeliveryDetails, Done] {
        case (SubmitDeliveryDetails(userId, deliveryInfo), ctx, _) if userId == transaction.winner=>
          val submitted = DeliveryDetailsSubmitted(transaction.itemId, deliveryInfo)
          ctx.thenPersist(submitted)(_ => ctx.reply(Done))
        case (_, ctx, _) =>
          ctx.invalidCommand("Only the auction winner can submit delivery details")
          ctx.done
      }
      .onCommand[SetDeliveryPrice, Done] {
        case (SetDeliveryPrice(userId, deliveryPrice), ctx, _) if userId == transaction.creator =>
          ctx.thenPersist(DeliveryPriceUpdated(transaction.itemId, deliveryPrice))(_ => ctx.reply(Done))
        case (_, ctx, _) =>
          ctx.invalidCommand("Only the auction creator can submit delivery price")
          ctx.done
      }
      .onCommand[ApproveDeliveryDetails, Done] {
        case (ApproveDeliveryDetails(userId), ctx, _) if userId == transaction.creator
          && transaction.deliveryInfo.isDefined
          && transaction.deliveryPrice.isDefined =>
          ctx.thenPersist(DeliveryDetailsApproved(transaction.itemId))(_ => ctx.reply(Done))
        case (ApproveDeliveryDetails(userId), ctx, _) if userId == transaction.creator =>
          ctx.invalidCommand("The delivery info or price can not be empty")
          ctx.done
        case (_, ctx, _) =>
          ctx.invalidCommand("Only the auction creator can approve delivery details")
          ctx.done
      }
      .onEvent {
        case (DeliveryDetailsSubmitted(_, deliveryInfo), _) => Some(transaction.updateDeliveryInfo(deliveryInfo))
        case (DeliveryDetailsApproved(_), _) => Some(transaction.updateStatus(PaymentPending))
        case (DeliveryPriceUpdated(_, deliveryPrice), _) => Some(transaction.updateDeliveryPrice(deliveryPrice))
      }
      .orElse(getTransactionCommand(transaction))
  }

  private def paymentPending(transaction: Transaction) = {
    Actions()
      .onCommand[SubmitPaymentDetails, Done] {
        case (SubmitPaymentDetails(userId, paymentInfo), ctx, _) if userId == transaction.winner =>
          ctx.thenPersist(PaymentDetailsSubmitted(transaction.itemId, paymentInfo))(_ => ctx.reply(Done))
        case (_, ctx, _) =>
          ctx.invalidCommand("Only the auction winner can submit payment details")
          ctx.done
      }
      .onEvent {
        case (PaymentDetailsSubmitted(_, paymentInfo), _) =>
          Some(transaction.updatePayment(paymentInfo).updateStatus(PaymentSubmitted))
        case (DeliveryDetailsApproved(_), _) =>
          Some(transaction.updateStatus(PaymentPending))
      }
      .orElse(getTransactionCommand(transaction))
  }

  private def paymentSubmitted(transaction: Transaction) = {
    Actions()
      .onCommand[SubmitPaymentStatus, Done] {
        case (SubmitPaymentStatus(userId, status), ctx, _) if userId == transaction.creator =>
          status match {
            case PaymentInfoStatus.Approved =>
              ctx.thenPersist(PaymentApproved(transaction.itemId))(_ => ctx.reply(Done))
            case PaymentInfoStatus.Rejected =>
              ctx.thenPersist(PaymentRejected(transaction.itemId))(_ => ctx.reply(Done))
          }
        case (_, ctx, _) =>
          ctx.invalidCommand("Only the auction creator can submit payment status")
          ctx.done
      }
      .onEvent {
        case (PaymentApproved(_), _) => Some(transaction.updateStatus(PaymentConfirmed))
        case (PaymentRejected(_), _) => Some(transaction.updateStatus(PaymentPending))
      }
      .orElse(getTransactionCommand(transaction))
  }

  private def paymentConfirmed(transaction: Transaction) = getTransactionCommand(transaction)
}

sealed trait TransactionEvent extends AggregateEvent[TransactionEvent] {
  override def aggregateTag: AggregateEventShards[TransactionEvent] = TransactionEvent.Tag
}

object TransactionEvent {
  val Tag = AggregateEventTag.sharded[TransactionEvent](numShards = 4)
}

trait TransactionCommand

case class Transaction
(
  itemId: UUID,
  creator: UUID,
  winner: UUID,
  itemData: ItemSummary,
  itemPrice: Int,
  deliveryInfo: Option[DeliveryInfo],
  deliveryPrice: Option[Int],
  paymentInfo: Option[PaymentInfo],
  status: TransactionInfoStatus.Status
) {

  def updateDeliveryInfo(newDeliveryInfo: DeliveryInfo): Transaction = {
    copy(deliveryInfo = Some(newDeliveryInfo))
  }

  def updateDeliveryPrice(newDeliveryPrice: Int): Transaction = {
    copy(deliveryPrice = Some(newDeliveryPrice))
  }

  def updatePayment(newPayment: PaymentInfo): Transaction = {
    copy(paymentInfo = Some(newPayment))
  }

  def updateStatus(newStatus: TransactionInfoStatus.Status): Transaction = {
    copy(status = newStatus)
  }
}

object Transaction {
  implicit val format: Format[Transaction] = Json.format
}

case class GetTransaction(userId: UUID) extends TransactionCommand with ReplyType[Option[Transaction]]

object GetTransaction{
  implicit val format: Format[GetTransaction] = Json.format
}

case class StartTransaction(transaction: Transaction) extends TransactionCommand with ReplyType[Done]

object StartTransaction {
  implicit val format: Format[StartTransaction] = Json.format
}

case class SubmitDeliveryDetails(userId: UUID,  deliveryInfo: DeliveryInfo)
  extends TransactionCommand
    with ReplyType[Done]

object SubmitDeliveryDetails {
  implicit val format: Format[SubmitDeliveryDetails] = Json.format
}

case class SetDeliveryPrice(userId: UUID, deliveryPrice: Int) extends TransactionCommand with ReplyType[Done]

object SetDeliveryPrice {
  implicit val format: Format[SetDeliveryPrice] = Json.format
}

case class ApproveDeliveryDetails(userId: UUID) extends TransactionCommand with ReplyType[Done]

object ApproveDeliveryDetails {
  implicit val format: Format[ApproveDeliveryDetails] = Json.format
}

case class SubmitPaymentDetails(userId: UUID, paymentInfo: PaymentInfo)
  extends TransactionCommand
    with ReplyType[Done]

object SubmitPaymentDetails {
  implicit val format: Format[SubmitPaymentDetails] = Json.format
}

case class SubmitPaymentStatus(userId: UUID, status: PaymentInfoStatus.Status)
  extends TransactionCommand
    with ReplyType[Done]

object SubmitPaymentStatus {
  implicit val format: Format[SubmitPaymentStatus] = Json.format
}

case class TransactionStarted(itemId: UUID, transaction: Transaction) extends TransactionEvent

object TransactionStarted {
  implicit val format: Format[TransactionStarted] = Json.format
}

case class DeliveryDetailsSubmitted(itemId: UUID, deliveryInfo: DeliveryInfo) extends TransactionEvent

object DeliveryDetailsSubmitted {
  implicit val format: Format[DeliveryDetailsSubmitted] = Json.format
}

case class DeliveryPriceUpdated(itemId: UUID, deliveryPrice: Int) extends TransactionEvent

object DeliveryPriceUpdated {
  implicit val format: Format[DeliveryPriceUpdated] = Json.format
}

case class DeliveryDetailsApproved(itemId: UUID) extends TransactionEvent

object DeliveryDetailsApproved {
  implicit val format: Format[DeliveryDetailsApproved] = Json.format
}

case class PaymentDetailsSubmitted(itemId: UUID, paymentInfo: PaymentInfo) extends TransactionEvent

object PaymentDetailsSubmitted {
  implicit val format: Format[PaymentDetailsSubmitted] = Json.format
}

case class PaymentApproved(itemId: UUID) extends TransactionEvent

object PaymentApproved {
  implicit val format: Format[PaymentApproved] = Json.format
}

case class PaymentRejected(itemId: UUID) extends TransactionEvent

object PaymentRejected {
  implicit val format: Format[PaymentRejected] = Json.format
}