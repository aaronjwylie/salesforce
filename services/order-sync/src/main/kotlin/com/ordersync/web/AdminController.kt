package com.ordersync.web

import com.ordersync.config.TopicProperties
import com.ordersync.kafka.DlqReplayService
import com.ordersync.kafka.ReplayReport
import com.ordersync.persistence.OutboxRepository
import com.ordersync.reconciliation.OrderReconciliationJob
import com.ordersync.reconciliation.ReconciliationReport
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class OutboxStatus(val depth: Long, val oldestAgeSeconds: Double)

/**
 * Operator endpoints.
 *
 * Unauthenticated here because this is a demo stack. In a real deployment these sit
 * behind the platform's auth and are not exposed outside the cluster — a public
 * "replay everything in the DLQ" button is an incident waiting to happen.
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val dlqReplay: DlqReplayService,
    private val outbox: OutboxRepository,
    private val topics: TopicProperties,
    private val reconciliation: ObjectProvider<OrderReconciliationJob>,
) {

    @GetMapping("/outbox")
    fun outboxStatus() = OutboxStatus(outbox.depth(), outbox.oldestAgeSeconds())

    /**
     * Runs reconciliation now rather than waiting for the nightly schedule.
     *
     * The operator's move after an outage longer than Salesforce's 72 hour replay
     * window, when the checkpoint points somewhere the stream will no longer honour.
     * Safe to run repeatedly: reconciled changes carry a deterministic event id, so a
     * second run republishes nothing.
     */
    @PostMapping("/reconcile")
    fun reconcile(@RequestParam(required = false) lookbackHours: Long?): ReconciliationReport {
        // ObjectProvider because the job is conditional on reconciliation being enabled.
        val job = reconciliation.getIfAvailable()
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Reconciliation is disabled (ordersync.reconciliation.enabled=false)",
            )

        if (lookbackHours == null) return job.reconcile()
        return job.reconcile(Instant.now().minus(lookbackHours, ChronoUnit.HOURS))
    }

    @PostMapping("/dlq/orders/replay")
    fun replayOrders(@RequestParam(defaultValue = "100") max: Int): ReplayReport =
        dlqReplay.replay(topics.ordersDlq, topics.orders, max)

    @PostMapping("/dlq/fulfillment/replay")
    fun replayFulfillment(@RequestParam(defaultValue = "100") max: Int): ReplayReport =
        dlqReplay.replay(topics.fulfillmentDlq, topics.fulfillment, max)
}
