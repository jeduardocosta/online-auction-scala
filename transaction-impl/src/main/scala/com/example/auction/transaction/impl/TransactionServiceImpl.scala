package com.example.auction.transaction.impl

import java.util.UUID

import com.example.auction.security.ServerSecurity.authenticated
import com.example.auction.transaction.api.TransactionInfoStatus.Status
import com.example.auction.transaction.api.{TransactionInfo, TransactionService}
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import com.lightbend.lagom.scaladsl.server.ServerServiceCall

import scala.concurrent.ExecutionContext

class TransactionServiceImpl(registry: PersistentEntityRegistry,
                             repository: TransactionRepository)
                            (implicit ec: ExecutionContext) extends TransactionService {

  override def submitDeliveryDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { deliveryInfo =>
    entityRef(itemId).ask(SubmitDeliveryDetails(userId, deliveryInfo))
  })

  override def setDeliveryPrice(itemId: UUID) = authenticated(userId => ServerServiceCall { deliveryPrice =>
    entityRef(itemId).ask(SetDeliveryPrice(userId, deliveryPrice))
  })

  override def approveDeliveryDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { _ =>
    entityRef(itemId).ask(ApproveDeliveryDetails(userId))
  })

  override def submitPaymentDetails(itemId: UUID) = authenticated(userId => ServerServiceCall { paymentInfo =>
    entityRef(itemId).ask(SubmitPaymentDetails(userId, paymentInfo))
  })

  override def submitPaymentStatus(itemId: UUID) = authenticated(userId => ServerServiceCall { status =>
    entityRef(itemId).ask(SubmitPaymentStatus(userId, status))
  })

  override def getTransaction(itemId: UUID) = authenticated(userId => ServerServiceCall { _ =>
    entityRef(itemId).ask(GetTransaction(userId)) map {
        case Some(transaction) =>
          TransactionInfo(transaction.itemId,
            transaction.winner,
            transaction.itemData,
            transaction.itemPrice,
            transaction.deliveryInfo,
            transaction.deliveryPrice,
            transaction.paymentInfo,
            transaction.status)
        case _ =>
          throw NotFound(s"Transaction with id $itemId")
    }
  })

  override def getTransactionsForUser(status: Status, pageNo: Option[Int], pageSize: Option[Int]) = {
    authenticated(itemId => ServerServiceCall { _ =>
      repository.getTransactionsForUser(itemId, status, pageNo.getOrElse(0), pageSize.getOrElse(default = 10))
    })
  }

  private def entityRef(itemId: UUID) = registry.refFor[TransactionEntity](itemId.toString)
}