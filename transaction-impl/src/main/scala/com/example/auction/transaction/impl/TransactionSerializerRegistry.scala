package com.example.auction.transaction.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}

object TransactionSerializerRegistry extends JsonSerializerRegistry {
  override def serializers = List(
    JsonSerializer[Transaction],

    JsonSerializer[GetTransaction],
    JsonSerializer[StartTransaction],
    JsonSerializer[SubmitDeliveryDetails],
    JsonSerializer[SetDeliveryPrice],
    JsonSerializer[ApproveDeliveryDetails],
    JsonSerializer[SubmitPaymentDetails],
    JsonSerializer[SubmitPaymentStatus],

    JsonSerializer[TransactionStarted],
    JsonSerializer[DeliveryDetailsSubmitted],
    JsonSerializer[DeliveryPriceUpdated],
    JsonSerializer[DeliveryDetailsApproved],
    JsonSerializer[PaymentDetailsSubmitted],
    JsonSerializer[PaymentApproved],
    JsonSerializer[PaymentRejected]
  )
}