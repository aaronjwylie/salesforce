package com.ordersync.persistence

import com.ordersync.domain.ReplayId
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JdbcReplayCheckpointStoreTest : PostgresTestSupport() {

    private val store = JdbcReplayCheckpointStore(jdbc, "/event/Order_Change__e")

    private fun position(label: String) = ReplayId.of(label.toByteArray())

    @Test
    fun `has no checkpoint before the first event`() {
        store.latest().shouldBeNull()
    }

    @Test
    fun `remembers the position it was given`() {
        store.save(position("p88214"))

        store.latest() shouldBe position("p88214")
    }

    @Test
    fun `advances as the stream progresses`() {
        store.save(position("p100"))
        store.save(position("p101"))
        store.save(position("p102"))

        store.latest() shouldBe position("p102")
    }

    /**
     * Replay ids are opaque bytes, so there is nothing to compare and no way to reject
     * an "older" one. Correctness comes from the caller: a single subscriber applying
     * events in stream order means the last write really is the furthest along.
     */
    @Test
    fun `takes the most recent write, since positions are not comparable`() {
        store.save(position("p500"))

        store.save(position("p499"))

        store.latest() shouldBe position("p499")
    }

    @Test
    fun `survives a position containing bytes that are not valid text`() {
        val binary = ReplayId.of(byteArrayOf(0, -17, -1, 42, 7))

        store.save(binary)

        store.latest() shouldBe binary
        store.latest()!!.bytes.toList() shouldBe listOf<Byte>(0, -17, -1, 42, 7)
    }

    @Test
    fun `keeps checkpoints for different streams apart`() {
        val other = JdbcReplayCheckpointStore(jdbc, "/data/OrderChangeEvent")

        store.save(position("p100"))
        other.save(position("p900"))

        store.latest() shouldBe position("p100")
        other.latest() shouldBe position("p900")
    }

    @Test
    fun `reports how stale the checkpoint is`() {
        store.save(position("p100"))

        store.ageSeconds()!! shouldBe (0.0 plusOrMinus 5.0)
    }
}
