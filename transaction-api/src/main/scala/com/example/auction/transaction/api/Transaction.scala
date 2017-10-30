package com.example.auction.transaction.api

import java.util.UUID

import com.example.auction.item.api.ItemSummary
import com.example.auction.utils.JsonFormats._
import com.lightbend.lagom.scaladsl.api.deser.PathParamSerializer
import play.api.libs.json.{Format, Json}

case class DeliveryInfo(
  addressLine1: String,
  addressLine2: String,
  city: String,
  state: String,
  postalCode: Int,
  country: String
)

object DeliveryInfo {
  implicit val format: Format[DeliveryInfo] = Json.format
}

case class PaymentInfo(comment: String)

object PaymentInfo {
  implicit val format: Format[PaymentInfo] = Json.format
}

object PaymentInfoStatus extends Enumeration {
  val Approved, Rejected = Value
  type Status = Value

  implicit val format: Format[Value] = enumFormat(this)
  implicit val pathParamSerializer: PathParamSerializer[Status] =
    PathParamSerializer.required("paymentInfoStatus")(withName)(_.toString)
}

case class TransactionInfo(
  itemId: UUID,
  winner: UUID,
  itemData: ItemSummary,
  itemPrice: Int,
  deliveryInfo: Option[DeliveryInfo],
  deliveryPrice: Option[Int],
  paymentInfo: Option[PaymentInfo],
  status: TransactionInfoStatus.Status
)

object TransactionInfo {
  implicit val format: Format[TransactionInfo] = Json.format
}

object TransactionInfoStatus extends Enumeration {
  val NegotiatingDelivery,
      PaymentPending,
      PaymentSubmitted,
      PaymentConfirmed,
      ItemDispatched,
      ItemReceived,
      Cancelled,
      Refunding,
      Refunded = Value

  type Status = Value

  implicit val format: Format[Value] = enumFormat(this)
  implicit val pathParamSerializer: PathParamSerializer[Status] =
    PathParamSerializer.required("transactionInfoStatus")(withName)(_.toString)
}

case class TransactionSummary(
  itemId: UUID,
  creatorId: UUID,
  winnerId: UUID,
  itemTitle: String,
  currencyId: String,
  itemPrice: Int,
  status: String
)

object TransactionSummary {
  implicit val format: Format[TransactionSummary] = Json.format
}