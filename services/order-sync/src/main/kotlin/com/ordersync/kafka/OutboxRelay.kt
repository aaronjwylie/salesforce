package com.ordersync.kafka

import com.ordersync.config.OutboxProperties
import com.ordersync.config.TopicProperties
import com.ordersync.persistence.OutboxRecord
import com.ordersync.persistence.OutboxRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox to Kafka.
 *
 * Runs inside a transaction so the rows it claimed stay locked until it is done —
 * that, plus `SKIP LOCKED`, is what lets several instances relay concurrently without
 * publishing anything twice.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val outboxProperties: OutboxProperties,
    private val topics: TopicProperties,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val published = Counter.builder("ordersync.outbox.published")
        .description("Rows successfully relayed to Kafka")
        .register(registry)

    private val failed = Counter.builder("ordersync.outbox.failed")
        .description("Publish attempts that threw")
        .register(registry)

    private val deadLettered = Counter.builder("ordersync.outbox.dead_lettered")
        .description("Rows abandoned to the DLQ after exhausting attempts")
        .register(registry)

    @Scheduled(fixedDelayString = "\${ordersync.outbox.poll-interval-ms:500}")
    @Transactional
    fun drain() {
        val batch = outbox.claimBatch(outboxProperties.batchSize)
        if (batch.isEmpty()) return

        val drained = mutableListOf<Long>()

        for (record in batch) {
            when (relay(record)) {
                Outcome.PUBLISHED, Outcome.DEAD_LETTERED -> drained += record.id
                // Stop at the first genuine failure. Continuing would let a later event
                // for the same order overtake this one, and order is the one guarantee
                // downstream consumers are entitled to.
                Outcome.RETRY -> break
            }
        }

        outbox.delete(drained)
    }

    private fun relay(record: OutboxRecord): Outcome =
        try {
            kafka.send(record.topic, record.messageKey, record.payload)
                .get(outboxProperties.publishTimeoutMs, TimeUnit.MILLISECONDS)
            published.increment()
            Outcome.PUBLISHED
        } catch (e: Exception) {
            failed.increment()
            handleFailure(record, e)
        }

    private fun handleFailure(record: OutboxRecord, e: Exception): Outcome {
        val attempts = record.attempts + 1
        outbox.recordFailure(record.id, e.message ?: e::class.java.name)

        if (attempts < outboxProperties.maxAttempts) {
            log.warn(
                "Outbox row {} failed on attempt {} of {}: {}",
                record.id, attempts, outboxProperties.maxAttempts, e.message,
            )
            return Outcome.RETRY
        }

        // A row we can never publish must not block every order behind it. Park it and
        // move on; the DLQ replay endpoint is how it comes back.
        return try {
            kafka.send(topics.ordersDlq, record.messageKey, record.payload)
                .get(outboxProperties.publishTimeoutMs, TimeUnit.MILLISECONDS)
            deadLettered.increment()
            log.error(
                "Outbox row {} dead-lettered to {} after {} attempts: {}",
                record.id, topics.ordersDlq, attempts, e.message,
            )
            Outcome.DEAD_LETTERED
        } catch (dlqFailure: Exception) {
            // If even the DLQ is unreachable, Kafka is down entirely. Keep the row.
            log.error("Outbox row {} could not be dead-lettered: {}", record.id, dlqFailure.message)
            Outcome.RETRY
        }
    }

    private enum class Outcome { PUBLISHED, DEAD_LETTERED, RETRY }
}
