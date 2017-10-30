package com.example.auction.transaction.api

import java.util.UUID

import akka.{Done, NotUsed}
import com.example.auction.security.SecurityHeaderFilter
import com.example.auction.utils.PaginatedSequence
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}

trait TransactionService extends Service {

  def submitDeliveryDetails(itemId: UUID): ServiceCall[DeliveryInfo, Done]

  def setDeliveryPrice(itemId: UUID): ServiceCall[Int, Done]

  def approveDeliveryDetails(itemId: UUID): ServiceCall[NotUsed, Done]

  def submitPaymentDetails(itemId: UUID): ServiceCall[PaymentInfo, Done]

  def submitPaymentStatus(itemId: UUID): ServiceCall[PaymentInfoStatus.Status, Done]

  def getTransaction(itemId: UUID): ServiceCall[NotUsed, TransactionInfo]

  def getTransactionsForUser(status: TransactionInfoStatus.Status,
                             pageNo: Option[Int],
                             pageSize: Option[Int]): ServiceCall[NotUsed, PaginatedSequence[TransactionSummary]]

  final override def descriptor: Descriptor = {
    import Service._

    named("transaction").withCalls(
      pathCall("/api/transaction/:id/deliverydetails", submitDeliveryDetails _),
      pathCall("/api/transaction/:id/deliveryprice", setDeliveryPrice _),
      pathCall("/api/transaction/:id/approvedelivery", approveDeliveryDetails _),
      pathCall("/api/transaction/:id/paymentdetails", submitPaymentDetails _),
      pathCall("/api/transaction/:id/paymentstatus", submitPaymentStatus _),
      pathCall("/api/transaction/:id", getTransaction _),
      pathCall("/api/transaction?status&pageNo&pageSize", getTransactionsForUser _)
    ).withHeaderFilter(SecurityHeaderFilter.Composed)
  }
}