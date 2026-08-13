package com.ordersync.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.ordersync.domain.FulfillmentEvent
import com.ordersync.salesforce.SalesforceCompositeClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * ERP to Salesforce.
 *
 * A **batch** listener, unlike [OrderEventConsumer]. The direction matters: writing to
 * Salesforce costs API calls against a daily allocation, so the whole poll is upserted
 * in one Composite call. Reading from the ERP has no such constraint, which is why the
 * other direction stays one-at-a-time and keeps its simpler failure semantics.
 */
@Component
class FulfillmentConsumer(
    private val salesforce: SalesforceCompositeClient,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val applied = registry.counter("ordersync.salesforce.fulfillment_applied")
    private val rejected = registry.counter("ordersync.salesforce.fulfillment_rejected")

    @KafkaListener(
        topics = ["\${ordersync.topics.fulfillment}"],
        groupId = "order-sync-salesforce",
        containerFactory = "batchListenerContainerFactory",
        batch = "true",
    )
    fun onFulfillmentBatch(payloads: List<String>) {
        if (payloads.isEmpty()) return

        val events = payloads.map { objectMapper.readValue(it, FulfillmentEvent::class.java) }

        // Last update wins per order: if the same order went PICKED then SHIPPED inside
        // one poll, sending both wastes half the batch on a value we immediately
        // overwrite — and Salesforce rejects duplicate external ids in one call outright.
        val latestPerOrder = events
            .groupBy { it.erpOrderId }
            .map { (_, updates) -> updates.maxBy { it.occurredAt } }

        val results = salesforce.upsertFulfillment(latestPerOrder)

        val succeeded = results.count { it.success }
        applied.increment(succeeded.toDouble())
        rejected.increment((results.size - succeeded).toDouble())

        log.info(
            "Applied {} of {} fulfillment updates ({} events collapsed)",
            succeeded, results.size, events.size - latestPerOrder.size,
        )
    }
}
