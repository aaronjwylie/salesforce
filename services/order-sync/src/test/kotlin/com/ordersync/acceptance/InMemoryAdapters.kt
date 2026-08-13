package com.ordersync.acceptance

import com.ordersync.domain.OrderEvent
import com.ordersync.domain.OrderEventPublisher
import com.ordersync.domain.ProcessedEventStore
import com.ordersync.domain.ReplayCheckpointStore
import com.ordersync.domain.ReplayId

/**
 * In-memory stand-ins for the real adapters. The acceptance tests exercise business
 * rules, so they run against these in milliseconds; the Postgres and Kafka adapters
 * get their own Testcontainers contract tests.
 */
class InMemoryProcessedEventStore : ProcessedEventStore {
    private val seen = mutableSetOf<String>()
    override fun markProcessed(eventId: String): Boolean = seen.add(eventId)
}

class RecordingOrderEventPublisher : OrderEventPublisher {
    val published = mutableListOf<OrderEvent>()
    override fun publish(event: OrderEvent) {
        published += event
    }
}

class InMemoryReplayCheckpointStore : ReplayCheckpointStore {
    private var replayId: ReplayId? = null
    override fun save(replayId: ReplayId) {
        this.replayId = replayId
    }

    override fun latest(): ReplayId? = replayId
}
