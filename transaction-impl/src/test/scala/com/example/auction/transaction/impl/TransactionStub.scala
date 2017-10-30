package com.example.auction.transaction.impl

import java.util.UUID

import com.datastax.driver.core.utils.UUIDs
import com.example.auction.item.api.{ItemStatus, ItemSummary}
import com.example.auction.transaction.api.{DeliveryInfo, PaymentInfo, TransactionInfoStatus}

trait TransactionStub {
  val itemId = UUIDs.timeBased
  val creatorId = UUID.randomUUID
  val winnerId = UUID.randomUUID

  val userId = winnerId

  def itemData(status: Option[ItemStatus.Status] = None) = ItemSummary(
    id = UUIDs.timeBased,
    "title",
    "currencyId",
    reservePrice = 10,
    status.getOrElse(ItemStatus.Auction)
  )

  val deliveryInfo = DeliveryInfo(
    "address1", "address2", "city", "state", postalCode = 123, "country"
  )

  val deliveryPrice = 18

  val transaction = Transaction(
    itemId, creatorId, winnerId,
    itemData(), itemPrice = 20,
    deliveryInfo = None,
    deliveryPrice = None,
    paymentInfo = None,
    status = TransactionInfoStatus.NegotiatingDelivery
  )

  val paymentInfo = PaymentInfo("comment")
}