package com.ordersync.domain

import org.slf4j.LoggerFactory

/**
 * Translates Salesforce order changes into canonical order events.
 *
 * The three rules that matter, in order:
 *  1. Irrelevant statuses are dropped before touching the database.
 *  2. Events already seen are dropped — Salesforce delivers at-least-once.
 *  3. The replay checkpoint advances for every event we successfully *handled*,
 *     including ones we deliberately ignored, but never for one that blew up.
 */
class OrderChangeProcessor(
    private val processedEvents: ProcessedEventStore,
    private val publisher: OrderEventPublisher,
    private val checkpoints: ReplayCheckpointStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(change: SalesforceOrderChange): ProcessResult {
        val result = handle(change)

        // Deliberately outside handle(): if handle() throws we must NOT advance, so the
        // event is redelivered. But a skipped draft still has to move the stream on,
        // or every restart re-reads the drafts we already decided to ignore.
        //
        // Null means this change did not come from the stream (reconciliation). Writing
        // a position for it would move the checkpoint somewhere the stream never was.
        change.replayId?.let { checkpoints.save(it) }

        return result
    }

    private fun handle(change: SalesforceOrderChange): ProcessResult {
        // Relevance first: it is a pure check, so drafts never cost us a dedupe row.
        if (!change.status.isErpRelevant) {
            log.debug("Skipping order {} in status {}", change.orderNumber, change.status)
            return ProcessResult.Skipped("status ${change.status} is not relevant to the ERP")
        }

        if (!processedEvents.markProcessed(change.eventId)) {
            log.debug("Ignoring redelivery of event {} for order {}", change.eventId, change.orderNumber)
            return ProcessResult.Duplicate
        }

        val event = OrderEvent.from(change)
        publisher.publish(event)
        log.info("Published order {} in status {}", event.orderNumber, event.status)
        return ProcessResult.Published(event)
    }
}
