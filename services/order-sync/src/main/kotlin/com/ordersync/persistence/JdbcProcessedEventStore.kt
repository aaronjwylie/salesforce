package com.ordersync.persistence

import com.ordersync.domain.ProcessedEventStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Deduplication backed by the `processed_event` primary key.
 *
 * The check and the insert are one statement on purpose. A `SELECT` followed by an
 * `INSERT` would let two instances processing the same redelivery both see "not
 * present" and both publish; `ON CONFLICT DO NOTHING` makes the database arbitrate.
 */
@Repository
class JdbcProcessedEventStore(private val jdbc: JdbcTemplate) : ProcessedEventStore {

    override fun markProcessed(eventId: String): Boolean = markProcessed(eventId, "")

    fun markProcessed(eventId: String, orderNumber: String): Boolean {
        val inserted = jdbc.update(
            """
            INSERT INTO processed_event (event_id, order_number)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            eventId,
            orderNumber,
        )
        return inserted == 1
    }

    /** Retention: Salesforce cannot redeliver beyond its replay window, so older rows are dead weight. */
    fun pruneOlderThanDays(days: Int): Int =
        jdbc.update("DELETE FROM processed_event WHERE processed_at < now() - make_interval(days => ?)", days)
}
