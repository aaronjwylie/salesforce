package com.ordersync.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OutboxRepositoryTest : PostgresTestSupport() {

    private val outbox = OutboxRepository(jdbc)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Test
    fun `hands back what was enqueued`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"orderNumber":"SO-1001"}""")

        val claimed = transactions.execute { outbox.claimBatch(10) }!!

        claimed shouldHaveSize 1
        claimed[0].topic shouldBe "orders.v1"
        claimed[0].messageKey shouldBe "SO-1001"
        claimed[0].attempts shouldBe 0
    }

    /**
     * The outbox must hand back exactly what it was given. It was originally a `jsonb`
     * column, which parses and re-serializes: the payload came back with whitespace
     * altered and keys reordered, so the message published to Kafka was not the message
     * the service produced. Fine until something signs or canonicalises it, and very
     * hard to spot at that point.
     */
    @Test
    fun `returns the payload byte for byte`() {
        val payload = """{"zeta":1,"alpha":"two","nested":{"b":false,"a":null},"spaced":  3}"""

        outbox.enqueue("orders.v1", "SO-1001", payload)

        val claimed = transactions.execute { outbox.claimBatch(1) }!!.single()
        claimed.payload shouldBe payload
    }

    @Test
    fun `still refuses a payload that is not json`() {
        shouldThrow<Exception> {
            outbox.enqueue("orders.v1", "SO-1001", "definitely not json")
        }
    }

    @Test
    fun `drains oldest first`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"n":1}""")
        outbox.enqueue("orders.v1", "SO-1002", """{"n":2}""")
        outbox.enqueue("orders.v1", "SO-1003", """{"n":3}""")

        val claimed = transactions.execute { outbox.claimBatch(2) }!!

        claimed.map { it.messageKey } shouldBe listOf("SO-1001", "SO-1002")
    }

    @Test
    fun `forgets rows once they are published`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"n":1}""")
        outbox.enqueue("orders.v1", "SO-1002", """{"n":2}""")
        val claimed = transactions.execute { outbox.claimBatch(10) }!!

        outbox.delete(claimed.map { it.id })

        outbox.depth() shouldBe 0L
    }

    /**
     * Two relay instances must not publish the same row twice. The second one should
     * step over what the first has claimed rather than block behind it.
     */
    @Test
    fun `does not hand the same row to two relays`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"n":1}""")
        val firstHasClaimed = CountDownLatch(1)
        val secondHasTried = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val firstRelay = pool.submit<List<OutboxRecord>> {
                transactions.execute {
                    val rows = outbox.claimBatch(10)
                    firstHasClaimed.countDown()
                    // Hold the lock while the second relay looks.
                    secondHasTried.await(10, TimeUnit.SECONDS)
                    rows
                }!!
            }

            firstHasClaimed.await(10, TimeUnit.SECONDS)
            val secondRelay = transactions.execute { outbox.claimBatch(10) }!!
            secondHasTried.countDown()

            firstRelay.get(10, TimeUnit.SECONDS) shouldHaveSize 1
            secondRelay shouldHaveSize 0
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `counts a failed publish without losing the row`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"n":1}""")
        val id = transactions.execute { outbox.claimBatch(1) }!!.single().id

        outbox.recordFailure(id, "broker unreachable")

        val reclaimed = transactions.execute { outbox.claimBatch(1) }!!.single()
        reclaimed.attempts shouldBe 1
        jdbc.queryForObject("SELECT last_error FROM outbox WHERE id = ?", String::class.java, id) shouldBe
            "broker unreachable"
    }

    @Test
    fun `exposes depth and age for alerting`() {
        outbox.enqueue("orders.v1", "SO-1001", """{"n":1}""")
        jdbc.update("UPDATE outbox SET created_at = now() - interval '30 seconds'")

        outbox.depth() shouldBe 1L
        outbox.oldestAgeSeconds() shouldBeGreaterThan 25.0
    }

    @Test
    fun `reports zero age when there is nothing to drain`() {
        outbox.oldestAgeSeconds() shouldBe 0.0
    }
}
