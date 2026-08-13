package com.ordersync.reconciliation

import com.ordersync.application.OrderChangeService
import com.ordersync.config.ReconciliationProperties
import com.ordersync.domain.ProcessResult
import com.ordersync.salesforce.SalesforceQueryClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ReconciliationReport(
    val examined: Int,
    val republished: Int,
    val alreadySeen: Int,
    val skipped: Int,
)

/**
 * The safety net for events the stream can no longer deliver.
 *
 * Salesforce expires replay ids after 72 hours. Past that the subscription cannot be
 * resumed at all, and everything that happened during the outage is simply gone from
 * the stream — the checkpoint points at a position Salesforce will no longer honour.
 * A weekend outage is entirely plausible, which makes this job load-bearing rather
 * than defensive.
 *
 * It re-derives order state from SOQL and feeds it through the same processor the
 * stream uses. Deduplication makes the overlap harmless: orders already handled carry
 * an event id the ledger has seen, so only the genuinely missing ones reach the ERP.
 */
@Component
@ConditionalOnProperty(prefix = "ordersync.reconciliation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OrderReconciliationJob(
    private val query: SalesforceQueryClient,
    private val orderChanges: OrderChangeService,
    private val properties: ReconciliationProperties,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val republished = registry.counter("ordersync.reconciliation.republished")
    private val runs = registry.counter("ordersync.reconciliation.runs")

    @Scheduled(cron = "\${ordersync.reconciliation.cron:0 30 2 * * *}")
    fun scheduledRun() {
        runCatching { reconcile() }
            .onFailure { log.error("Reconciliation failed: {}", it.message, it) }
    }

    fun reconcile(since: Instant = Instant.now().minus(properties.lookbackHours, ChronoUnit.HOURS)): ReconciliationReport {
        runs.increment()
        log.info("Reconciling orders modified since {}", since)

        val orders = query.ordersModifiedSince(since)
        var republishedCount = 0
        var alreadySeen = 0
        var skipped = 0

        for (order in orders) {
            when (orderChanges.handle(order)) {
                is ProcessResult.Published -> republishedCount++
                ProcessResult.Duplicate -> alreadySeen++
                is ProcessResult.Skipped -> skipped++
            }
        }

        republished.increment(republishedCount.toDouble())

        val report = ReconciliationReport(orders.size, republishedCount, alreadySeen, skipped)
        log.info(
            "Reconciliation examined {} orders: {} republished, {} already seen, {} skipped",
            report.examined, report.republished, report.alreadySeen, report.skipped,
        )
        return report
    }
}
