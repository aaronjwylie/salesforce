package com.ordersync.domain

import java.util.Base64

/**
 * A Salesforce Pub/Sub stream position.
 *
 * Deliberately **not** a number. The Pub/Sub API declares `replay_id` as `bytes` and
 * documents it as opaque: it is not guaranteed to be ordered, comparable, or even
 * stable in width across releases. Modelling it as a `Long` reads fine in tests and
 * then quietly breaks against a real org.
 *
 * The practical consequence is that a checkpoint cannot be "the greater of the two" —
 * there is no greater. Correctness comes from processing a single subscription's events
 * in the order the stream delivers them, so the last one written is by definition the
 * furthest along. See [com.ordersync.persistence.JdbcReplayCheckpointStore].
 */
@JvmInline
value class ReplayId private constructor(private val encoded: String) {

    /** The wire form, for handing back to the Pub/Sub API. */
    val bytes: ByteArray get() = Base64.getDecoder().decode(encoded)

    /** The storage form. Base64 so an opaque blob survives a VARCHAR column intact. */
    fun asStored(): String = encoded

    override fun toString(): String = encoded

    companion object {
        fun of(bytes: ByteArray): ReplayId = ReplayId(Base64.getEncoder().encodeToString(bytes))

        fun fromStored(value: String): ReplayId = ReplayId(value)
    }
}
