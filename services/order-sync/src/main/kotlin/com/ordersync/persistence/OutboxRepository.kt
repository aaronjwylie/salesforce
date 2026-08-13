package com.ordersync.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

data class OutboxRecord(
    val id: Long,
    val topic: String,
    val messageKey: String,
    val payload: String,
    val createdAt: Instant,
    val attempts: Int,
)

@Repository
class OutboxRepository(private val jdbc: JdbcTemplate) {

    fun enqueue(topic: String, messageKey: String, payload: String) {
        jdbc.update(
            "INSERT INTO outbox (topic, message_key, payload) VALUES (?, ?, ?::jsonb)",
            topic,
            messageKey,
            payload,
        )
    }

    /**
     * Claims up to [limit] rows for this relay instance.
     *
     * `FOR UPDATE SKIP LOCKED` is what makes the relay horizontally scalable: a second
     * instance steps over rows the first has claimed instead of blocking on them, and
     * a crashed instance releases its rows when the transaction dies. Must be called
     * inside a transaction — the locks are held until it commits.
     */
    fun claimBatch(limit: Int): List<OutboxRecord> =
        jdbc.query(
            """
            SELECT id, topic, message_key, payload, created_at, attempts
              FROM outbox
             ORDER BY id
             LIMIT ?
               FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ ->
                OutboxRecord(
                    id = rs.getLong("id"),
                    topic = rs.getString("topic"),
                    messageKey = rs.getString("message_key"),
                    payload = rs.getString("payload"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    attempts = rs.getInt("attempts"),
                )
            },
            limit,
        )

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        // Explicit placeholders rather than `= ANY(?)`: JdbcTemplate will not convert a
        // Kotlin array into a java.sql.Array, and the batch is bounded by the relay's
        // page size so the statement never grows unreasonably.
        val placeholders = ids.joinToString(",") { "?" }
        jdbc.update("DELETE FROM outbox WHERE id IN ($placeholders)", *ids.toTypedArray())
    }

    /** Records a failed publish so a poison row is visible rather than silently retried forever. */
    fun recordFailure(id: Long, error: String) {
        jdbc.update(
            "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
            error.take(2000),
            id,
        )
    }

    /** Alert on this: a rising outbox means Kafka is unreachable or the relay is dead. */
    fun depth(): Long = jdbc.queryForObject("SELECT count(*) FROM outbox", Long::class.java) ?: 0

    /** Age of the oldest undrained row, in seconds. The number that actually pages someone. */
    fun oldestAgeSeconds(): Double =
        jdbc.queryForObject(
            "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM outbox",
            Double::class.java,
        ) ?: 0.0
}
