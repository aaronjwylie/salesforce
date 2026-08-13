package com.ordersync.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.ordersync.config.TopicProperties
import com.ordersync.domain.OrderEvent
import com.ordersync.domain.OrderEventPublisher
import org.springframework.stereotype.Component

/**
 * The production [OrderEventPublisher]: writes to the outbox, not to Kafka.
 *
 * This is what makes the dedupe insert and the outgoing event a single atomic unit —
 * the caller's transaction covers both, so there is no window where one landed and the
 * other did not. [com.ordersync.kafka.OutboxRelay] does the actual publishing.
 */
@Component
class OutboxOrderEventPublisher(
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val topics: TopicProperties,
) : OrderEventPublisher {

    override fun publish(event: OrderEvent) {
        outbox.enqueue(
            topic = topics.orders,
            // Partition by order number so one order's events stay in order, while
            // different orders still spread across partitions.
            messageKey = event.orderNumber,
            payload = objectMapper.writeValueAsString(event),
        )
    }
}
