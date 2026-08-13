package com.ordersync.salesforce

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.ordersync.config.SalesforceProperties
import com.ordersync.domain.OrderStatus
import com.ordersync.domain.ReplayId
import com.ordersync.domain.SalesforceOrderChange
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter

@JsonIgnoreProperties(ignoreUnknown = true)
data class QueryResponse(
    val totalSize: Int = 0,
    val done: Boolean = true,
    val nextRecordsUrl: String? = null,
    val records: List<OrderRecord> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderRecord(
    @JsonProperty("Id") val id: String,
    @JsonProperty("OrderNumber") val orderNumber: String,
    @JsonProperty("Status") val status: String,
    @JsonProperty("TotalAmount") val totalAmount: BigDecimal? = null,
    @JsonProperty("LastModifiedDate") val lastModifiedDate: String,
    @JsonProperty("Account") val account: AccountRef? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountRef(@JsonProperty("External_Id__c") val externalId: String? = null)

/**
 * SOQL over the REST API, used only by reconciliation.
 *
 * The event stream is the normal path; this exists for when the stream cannot be
 * resumed at all. See [com.ordersync.reconciliation.OrderReconciliationJob].
 */
@Component
class SalesforceQueryClient(
    restClientBuilder: RestClient.Builder,
    private val auth: SalesforceAuth,
    private val properties: SalesforceProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = restClientBuilder.build()

    fun ordersModifiedSince(since: Instant): List<SalesforceOrderChange> {
        val soql = """
            SELECT Id, OrderNumber, Status, TotalAmount, LastModifiedDate, Account.External_Id__c
            FROM Order
            WHERE LastModifiedDate >= ${DateTimeFormatter.ISO_INSTANT.format(since)}
              AND Status != 'Draft'
            ORDER BY LastModifiedDate ASC
        """.trimIndent().replace("\n", " ")

        val records = mutableListOf<OrderRecord>()
        var url: String? = "/services/data/$API_VERSION/query?q=" +
            UriUtils.encodeQueryParam(soql, StandardCharsets.UTF_8)

        // Salesforce pages at 2000 records. Following nextRecordsUrl is not optional —
        // stopping at the first page would silently reconcile only part of the backlog.
        while (url != null) {
            val page = fetchPage(url)
            records += page.records
            url = page.nextRecordsUrl
        }

        log.info("Reconciliation query returned {} orders modified since {}", records.size, since)
        return records.mapNotNull(::toOrderChange)
    }

    private fun fetchPage(path: String): QueryResponse {
        val session = auth.current()
        return restClient.get()
            .uri("${session.instanceUrl}$path")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${session.accessToken}")
            .retrieve()
            .body(QueryResponse::class.java)
            ?: QueryResponse()
    }

    private fun toOrderChange(record: OrderRecord): SalesforceOrderChange? {
        val status = runCatching { OrderStatus.valueOf(record.status.uppercase()) }.getOrNull()
        if (status == null) {
            log.warn("Skipping order {} with unmapped status '{}'", record.orderNumber, record.status)
            return null
        }

        return SalesforceOrderChange(
            // Deterministic, not random. Reconciliation runs repeatedly and overlaps
            // the event stream by design; a stable id means the dedupe table absorbs
            // the overlap instead of the ERP seeing every order twice.
            eventId = "recon:${record.id}:${record.lastModifiedDate}",
            // No stream position: this order was queried, not streamed. The processor
            // leaves the checkpoint alone when this is null.
            replayId = null,
            orderId = record.id,
            orderNumber = record.orderNumber,
            accountExternalId = record.account?.externalId ?: "",
            status = status,
            totalAmount = record.totalAmount ?: BigDecimal.ZERO,
            currencyCode = properties.defaultCurrency,
            occurredAt = Instant.parse(record.lastModifiedDate.replace("+0000", "Z")),
        )
    }

    companion object {
        private const val API_VERSION = "v62.0"
    }
}
