package com.ordersync.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.ordersync.domain.OrderEvent
import com.ordersync.erp.ErpClient
import com.ordersync.erp.ErpOrderRequest
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Consumes canonical order events and pushes them into the ERP.
 *
 * Deliberately has no try/catch: retry, backoff and dead-lettering are the container's
 * job ([KafkaConfig.errorHandler]). Swallowing exceptions here would acknowledge the
 * offset for a message that was never delivered.
 */
@Component
class OrderEventConsumer(
    private val erp: ErpClient,
    private val objectMapper: ObjectMapper,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val forwarded = registry.counter("ordersync.erp.forwarded")
    private val latency = Timer.builder("ordersync.erp.latency")
        .description("Time to push one order into the ERP")
        .register(registry)

    @KafkaListener(
        topics = ["\${ordersync.topics.orders}"],
        groupId = "order-sync-erp",
    )
    fun onOrderEvent(payload: String) {
        val event = objectMapper.readValue(payload, OrderEvent::class.java)

        val response = latency.recordCallable {
            erp.upsertOrder(ErpOrderRequest.from(event))
        }!!

        forwarded.increment()
        log.info("Order {} is {} in the ERP", event.orderNumber, response.erpOrderId)
    }
}
