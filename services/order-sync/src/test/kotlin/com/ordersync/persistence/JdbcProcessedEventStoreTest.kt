package com.ordersync.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdbcProcessedEventStoreTest : PostgresTestSupport() {

    private val store = JdbcProcessedEventStore(jdbc)

    @Test
    fun `reports the first sighting of an event`() {
        store.markProcessed("evt-1", "SO-1001") shouldBe true
    }

    @Test
    fun `reports a redelivery as already seen`() {
        store.markProcessed("evt-1", "SO-1001")

        store.markProcessed("evt-1", "SO-1001") shouldBe false
    }

    @Test
    fun `treats different events independently`() {
        store.markProcessed("evt-1", "SO-1001") shouldBe true
        store.markProcessed("evt-2", "SO-1001") shouldBe true
    }

    /**
     * The one that matters. Two service instances receiving the same redelivery must
     * not both conclude they are first — otherwise the ERP gets the order twice.
     */
    @Test
    fun `lets exactly one caller win a race on the same event`() {
        val eventId = UUID.randomUUID().toString()
        val threads = 16
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val tasks = (1..threads).map {
                Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    store.markProcessed(eventId, "SO-1001")
                }
            }

            val winners = pool.invokeAll(tasks).count { it.get() }

            winners shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `prunes rows past the redelivery window`() {
        store.markProcessed("old", "SO-1001")
        store.markProcessed("recent", "SO-1002")
        jdbc.update("UPDATE processed_event SET processed_at = now() - interval '10 days' WHERE event_id = 'old'")

        val pruned = store.pruneOlderThanDays(7)

        pruned shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM processed_event", Long::class.java) shouldBe 1L
    }
}
