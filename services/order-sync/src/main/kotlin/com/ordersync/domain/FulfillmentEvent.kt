package com.ordersync.domain

import java.time.Instant

/**
 * A fulfillment update travelling the other way: ERP to Salesforce.
 *
 * Keyed on [erpOrderId] rather than a Salesforce id. The ERP has never been told what
 * Salesforce calls this order and should not have to be — that is exactly what the
 * `ERP_Order_Id__c` external id field on Order exists for.
 */
data class FulfillmentEvent(
    val eventId: String,
    val erpOrderId: String,
    val orderNumber: String,
    val fulfillmentStatus: String,
    val occurredAt: Instant,
)
