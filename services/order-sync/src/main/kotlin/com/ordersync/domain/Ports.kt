package com.ordersync.domain

/**
 * Deduplication store. Adapters back this with a unique index on the event id so the
 * check-and-insert is atomic across instances rather than a read-then-write race.
 */
interface ProcessedEventStore {
    /** @return true if this is the first time [eventId] has been seen. */
    fun markProcessed(eventId: String): Boolean
}

/**
 * Publishes canonical events. The production adapter writes to the transactional
 * outbox rather than straight to Kafka — see docs/adr/0001-transactional-outbox.md.
 */
interface OrderEventPublisher {
    fun publish(event: OrderEvent)
}

/**
 * Persists the Salesforce Pub/Sub replay position so a restart resumes the stream
 * instead of silently dropping everything that happened while we were down.
 */
interface ReplayCheckpointStore {
    fun save(replayId: ReplayId)
    fun latest(): ReplayId?
}
