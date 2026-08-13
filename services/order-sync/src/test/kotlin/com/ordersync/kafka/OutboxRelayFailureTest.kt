package com.ordersync.kafka

import com.ordersync.config.OutboxProperties
import com.ordersync.config.TopicProperties
import com.ordersync.persistence.OutboxRepository
import com.ordersync.persistence.PostgresTestSupport
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture

/**
 * Real Postgres, faked broker. The failure paths are about what happens to the *row*,
 * so the database has to be real; making Kafka fail on demand is far easier with a mock.
 */
class OutboxRelayFailureTest : PostgresTestSupport() {

    private val topics = TopicProperties(orders = "orders.v1", ordersDlq = "orders.v1.DLQ")
    private val outbox = OutboxRepository(jdbc)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val kafka = mockk<KafkaTemplate<String, String>>()

    private val relay = OutboxRelay(
        outbox = outbox,
        kafka = kafka,
        outboxProperties = OutboxProperties(maxAttempts = 3),
        topics = topics,
        registry = SimpleMeterRegistry(),
    )

    private fun brokerDown() {
        every { kafka.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.failedFuture(RuntimeException("broker unreachable"))
    }

    private fun dlqOnlyReachable() {
        every { kafka.send(eq(topics.orders), any<String>(), any<String>()) } returns
            CompletableFuture.failedFuture(RuntimeException("serialization failed"))
        every { kafka.send(eq(topics.ordersDlq), any<String>(), any<String>()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
    }

    @Test
    fun `keeps the row when the broker is unreachable`() {
        brokerDown()
        outbox.enqueue(topics.orders, "SO-1001", """{"n":1}""")

        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 1L
        currentAttempts() shouldBe 1
    }

    @Test
    fun `records why the publish failed`() {
        brokerDown()
        outbox.enqueue(topics.orders, "SO-1001", """{"n":1}""")

        transactions.execute { relay.drain() }

        jdbc.queryForObject("SELECT last_error FROM outbox", String::class.java)!!
            .contains("broker unreachable") shouldBe true
    }

    /**
     * Ordering guarantee: if row 1 cannot be published, row 2 for the same order must
     * not overtake it. The relay stops rather than skipping ahead.
     */
    @Test
    fun `stops at the first failure instead of skipping ahead`() {
        brokerDown()
        outbox.enqueue(topics.orders, "SO-1001", """{"seq":1}""")
        outbox.enqueue(topics.orders, "SO-1001", """{"seq":2}""")

        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 2L
        // Only the first row was ever attempted.
        jdbc.queryForObject("SELECT count(*) FROM outbox WHERE attempts > 0", Long::class.java) shouldBe 1L
    }

    @Test
    fun `dead-letters a row that has exhausted its attempts`() {
        dlqOnlyReachable()
        outbox.enqueue(topics.orders, "SO-1001", """{"n":1}""")
        jdbc.update("UPDATE outbox SET attempts = 2")

        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 0L
    }

    /**
     * A poison row must not hold the queue forever, but it also must not disappear
     * while Kafka is down entirely — there would be nowhere for it to have gone.
     */
    @Test
    fun `keeps a poison row when even the DLQ is unreachable`() {
        brokerDown()
        outbox.enqueue(topics.orders, "SO-1001", """{"n":1}""")
        jdbc.update("UPDATE outbox SET attempts = 2")

        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 1L
    }

    @Test
    fun `lets later rows through once the poison one is parked`() {
        dlqOnlyReachable()
        outbox.enqueue(topics.orders, "SO-1001", """{"poison":true}""")
        jdbc.update("UPDATE outbox SET attempts = 2")
        outbox.enqueue(topics.ordersDlq, "SO-1002", """{"fine":true}""")

        transactions.execute { relay.drain() }

        outbox.depth() shouldBe 0L
    }

    private fun currentAttempts(): Int =
        jdbc.queryForObject("SELECT attempts FROM outbox", Int::class.java)!!
}
