package com.ordersync.domain

import java.math.BigDecimal
import java.time.Instant

/**
 * An order change as it arrives from the Salesforce Pub/Sub API.
 *
 * [eventId] is Salesforce's `EventUuid` and is the idempotency key: the Pub/Sub API
 * guarantees at-least-once delivery, so the same [eventId] can and will arrive twice.
 * [replayId] is the opaque stream position used to resume after a restart. It is null
 * for changes that did not come from the stream — reconciliation re-derives orders
 * from SOQL, and those have no position to record.
 */
data class SalesforceOrderChange(
    val eventId: String,
    val replayId: ReplayId?,
    val orderId: String,
    val orderNumber: String,
    val accountExternalId: String,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val currencyCode: String,
    val occurredAt: Instant,
)

enum class OrderStatus {
    DRAFT,
    ACTIVATED,
    FULFILLED,
    CANCELLED,
    ;

    /**
     * The ERP only cares about orders that have left draft. Forwarding drafts would
     * create orders that Sales may never activate.
     */
    val isErpRelevant: Boolean
        get() = this != DRAFT
}

/**
 * The canonical event published to Kafka. Deliberately decoupled from the Salesforce
 * payload so downstream consumers do not inherit Salesforce field naming or its
 * schema evolution.
 */
data class OrderEvent(
    val eventId: String,
    val orderNumber: String,
    /**
     * The Salesforce record id. Carried so the ERP's assigned id can be written back
     * against the right record — without it the two systems have no shared key and the
     * return path cannot find the order it is trying to update.
     */
    val salesforceOrderId: String,
    val accountExternalId: String,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val currencyCode: String,
    val occurredAt: Instant,
) {
    companion object {
        fun from(change: SalesforceOrderChange) = OrderEvent(
            eventId = change.eventId,
            orderNumber = change.orderNumber,
            salesforceOrderId = change.orderId,
            accountExternalId = change.accountExternalId,
            status = change.status,
            totalAmount = change.totalAmount,
            currencyCode = change.currencyCode,
            occurredAt = change.occurredAt,
        )
    }
}

sealed interface ProcessResult {
    /** The event was translated and handed to the publisher. */
    data class Published(val event: OrderEvent) : ProcessResult

    /** The event had already been processed; nothing was published. */
    data object Duplicate : ProcessResult

    /** The event was new but not relevant to the ERP. */
    data class Skipped(val reason: String) : ProcessResult
}
