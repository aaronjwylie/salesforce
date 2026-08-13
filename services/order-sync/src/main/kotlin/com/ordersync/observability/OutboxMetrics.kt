package com.ordersync.observability

import com.ordersync.persistence.OutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * The two numbers worth waking someone for.
 *
 * Depth alone is noisy — a burst of orders spikes it harmlessly. Age is the real
 * signal: if the oldest undrained row keeps getting older, the relay has stopped
 * making progress and orders are not reaching the ERP.
 */
@Component
class OutboxMetrics(registry: MeterRegistry, outbox: OutboxRepository) {

    init {
        Gauge.builder("ordersync.outbox.depth", outbox) { it.depth().toDouble() }
            .description("Rows waiting to be relayed to Kafka")
            .register(registry)

        Gauge.builder("ordersync.outbox.oldest_age_seconds", outbox) { it.oldestAgeSeconds() }
            .description("Age of the oldest undrained outbox row")
            .baseUnit("seconds")
            .register(registry)
    }
}
