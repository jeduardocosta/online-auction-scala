package com.example.auction.transaction.impl

import java.util.UUID

import akka.Done
import akka.stream.scaladsl.Flow
import com.example.auction.item.api.{AuctionFinished, ItemEvent, ItemService, ItemSummary}
import com.example.auction.transaction.api.TransactionInfoStatus
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry

import scala.concurrent.Future

class TransactionServiceSubscriber(persistentEntityRegistry: PersistentEntityRegistry,
                                   itemService: ItemService) {

  itemService
    .itemEvents
    .subscribe
    .atLeastOnce(Flow[ItemEvent].mapAsync(1) {
      case AuctionFinished(itemId, item) => item.auctionWinner match {
        case Some(winner) =>
          val transaction = Transaction(
            itemId,
            item.creator,
            winner,
            ItemSummary(itemId, item.title, item.currencyId, item.reservePrice, item.status),
            item.reservePrice,
            deliveryInfo = None,
            deliveryPrice = None,
            paymentInfo = None,
            status = TransactionInfoStatus.ItemReceived)

          entityRef(itemId).ask(StartTransaction(transaction))
        case _ => Future.successful(Done)
      }
      case _ => Future.successful(Done)
    })

  private def entityRef(itemId: UUID) = persistentEntityRegistry.refFor[TransactionEntity](itemId.toString)
}