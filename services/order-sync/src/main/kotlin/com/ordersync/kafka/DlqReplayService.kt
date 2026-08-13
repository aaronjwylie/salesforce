package com.ordersync.kafka

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ReplayReport(
    val dlqTopic: String,
    val targetTopic: String,
    val replayed: Int,
    val failed: Int,
)

/**
 * Drains a DLQ back onto its source topic.
 *
 * A dead letter queue nobody can empty is just a slower way of losing data. This is the
 * operator's undo button: fix the ERP mapping, deploy, replay. Offsets are committed
 * only after a successful republish, so a crash mid-replay repeats messages rather than
 * losing them — which the idempotent consumers downstream can absorb.
 */
@Service
class DlqReplayService(
    private val consumerFactory: ConsumerFactory<String, String>,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun replay(dlqTopic: String, targetTopic: String, max: Int): ReplayReport {
        var replayed = 0
        var failed = 0

        // A throwaway group id: this is a one-shot drain, not a durable subscription,
        // and reusing a fixed group would inherit whatever offsets a previous run left.
        consumerFactory.createConsumer("dlq-replay", "-${UUID.randomUUID()}").use { consumer ->
            consumer.subscribe(listOf(dlqTopic))

            // The first poll after subscribing usually returns nothing while the group
            // rebalances, so keep asking until the deadline rather than giving up.
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (replayed + failed < max && System.nanoTime() < deadline) {
                val records = consumer.poll(Duration.ofMillis(500))
                if (records.isEmpty) continue

                for (record in records) {
                    if (replayed + failed >= max) break
                    try {
                        kafkaTemplate.send(targetTopic, record.key(), record.value())
                            .get(10, TimeUnit.SECONDS)
                        replayed++
                    } catch (e: Exception) {
                        log.error("Could not replay {} from {}: {}", record.key(), dlqTopic, e.message)
                        failed++
                    }
                }

                // Commit only what we managed to republish.
                if (failed == 0) consumer.commitSync() else break
            }
        }

        log.info("Replayed {} messages from {} to {} ({} failed)", replayed, dlqTopic, targetTopic, failed)
        return ReplayReport(dlqTopic, targetTopic, replayed, failed)
    }
}
