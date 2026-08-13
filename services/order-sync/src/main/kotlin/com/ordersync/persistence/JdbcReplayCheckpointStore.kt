package com.ordersync.persistence

import com.ordersync.domain.ReplayCheckpointStore
import com.ordersync.domain.ReplayId
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Persists the Pub/Sub stream position.
 *
 * Last write wins, and that is not a shortcut. Replay ids are opaque bytes, so there
 * is no "greater" position to prefer — the guarantee comes from upstream instead: one
 * subscription, events applied in the order the stream delivered them, each inside the
 * transaction that also recorded its work. The most recently committed position is
 * therefore always the furthest along.
 *
 * The corollary is that running two subscribers against one stream name would corrupt
 * the checkpoint. That is enforced by there being a single subscriber per topic, not
 * by this table.
 */
@Repository
class JdbcReplayCheckpointStore(
    private val jdbc: JdbcTemplate,
    @Value("\${ordersync.salesforce.pubsub-topic}") private val streamName: String,
) : ReplayCheckpointStore {

    override fun save(replayId: ReplayId) {
        jdbc.update(
            """
            INSERT INTO replay_checkpoint (stream_name, replay_id)
            VALUES (?, ?)
            ON CONFLICT (stream_name) DO UPDATE
               SET replay_id  = EXCLUDED.replay_id,
                   updated_at = now()
            """.trimIndent(),
            streamName,
            replayId.asStored(),
        )
    }

    override fun latest(): ReplayId? =
        jdbc.query(
            "SELECT replay_id FROM replay_checkpoint WHERE stream_name = ?",
            { rs, _ -> ReplayId.fromStored(rs.getString("replay_id")) },
            streamName,
        ).firstOrNull()

    /**
     * Salesforce expires replay ids after 72 hours. Past that the stream cannot be
     * resumed and recovery is a full reconciliation, so the age of this row is
     * operationally interesting.
     */
    fun ageSeconds(): Double? =
        jdbc.query(
            "SELECT extract(epoch FROM now() - updated_at) AS age FROM replay_checkpoint WHERE stream_name = ?",
            { rs, _ -> rs.getDouble("age") },
            streamName,
        ).firstOrNull()
}
