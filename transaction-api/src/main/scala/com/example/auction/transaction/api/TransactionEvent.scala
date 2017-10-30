package com.example.auction.transaction.api

import java.util.UUID

import play.api.libs.json._

sealed trait TransactionEvent {
  val itemId: UUID
}

case class TransactionStarted(itemId: UUID, transaction: TransactionInfo) extends TransactionEvent

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

case class PaymentDetailsSubmitted(itemId: UUID, payment: PaymentInfo) extends TransactionEvent

object PaymentDetailsSubmitted {
  implicit val format: Format[PaymentDetailsSubmitted] = Json.format
}

case class PaymentApproved(itemId: UUID) extends TransactionEvent

object PaymentApproved {
  implicit val format: Format[PaymentApproved] = Json.format
}

case class PaymentRejected(itemId: UUID)

object PaymentRejected {
  implicit val format: Format[PaymentRejected] = Json.format
}