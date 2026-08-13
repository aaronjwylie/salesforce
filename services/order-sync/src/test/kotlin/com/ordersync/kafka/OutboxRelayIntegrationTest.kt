package com.ordersync.kafka

import com.ordersync.config.OutboxProperties
import com.ordersync.config.TopicProperties
import com.ordersync.persistence.OutboxRepository
import com.ordersync.persistence.PostgresTestSupport
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Real Postgres, real Kafka. Proves the relay actually moves bytes between them —
 * the one thing no amount of mocking establishes.
 */
class OutboxRelayIntegrationTest : PostgresTestSupport() {

    private val topics = TopicProperties(orders = "orders.v1", ordersDlq = "orders.v1.DLQ")
    private val outbox = OutboxRepository(jdbc)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))

    private val relay = OutboxRelay(
        outbox = outbox,
        kafka = KafkaTestCluster.kafkaTemplate(),
        outboxProperties = OutboxProperties(),
        topics = topics,
        registry = SimpleMeterRegistry(),
    )

    @Test
    fun `relays an enqueued event to Kafka and clears the row`() {
        val consumer = KafkaTestCluster.consumerFor(topics.orders)
        consumer.use {
            outbox.enqueue(topics.orders, "SO-1001", """{"orderNumber":"SO-1001","status":"ACTIVATED"}""")

            transactions.execute { relay.drain() }

            val records = KafkaTestCluster.drain(consumer, 1)
            records shouldHaveSize 1
            records[0].key() shouldBe "SO-1001"
            records[0].value() shouldBe """{"orderNumber":"SO-1001","status":"ACTIVATED"}"""
            outbox.depth() shouldBe 0L
        }
    }

    @Test
    fun `preserves order within a partition key`() {
        val consumer = KafkaTestCluster.consumerFor(topics.orders)
        consumer.use {
            outbox.enqueue(topics.orders, "SO-2001", """{"seq":1}""")
            outbox.enqueue(topics.orders, "SO-2001", """{"seq":2}""")
            outbox.enqueue(topics.orders, "SO-2001", """{"seq":3}""")

            transactions.execute { relay.drain() }

            val values = KafkaTestCluster.drain(consumer, 3).map { it.value() }
            values shouldBe listOf("""{"seq":1}""", """{"seq":2}""", """{"seq":3}""")
        }
    }

    @Test
    fun `does nothing when the outbox is empty`() {
        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 0L
    }
}
