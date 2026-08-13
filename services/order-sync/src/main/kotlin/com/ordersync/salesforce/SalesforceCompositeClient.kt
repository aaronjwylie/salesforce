package com.ordersync.salesforce

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.ordersync.domain.FulfillmentEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

class SalesforceUnavailableException(message: String) : RuntimeException(message)

class SalesforceRejectedException(message: String) : RuntimeException(message)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpsertResult(
    val id: String? = null,
    val success: Boolean = false,
    val created: Boolean = false,
    val errors: List<UpsertError> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpsertError(
    val statusCode: String = "",
    val message: String = "",
    val fields: List<String> = emptyList(),
)

/**
 * Writes fulfillment updates back into Salesforce.
 *
 * Uses the sObject Collections upsert endpoint rather than one REST call per order.
 * That is not micro-optimisation: Salesforce bills API *calls* against a daily
 * allocation that a Developer Edition org exhausts in the low thousands. Two hundred
 * orders as two hundred calls is a service that stops working by mid-afternoon; two
 * hundred orders as one call is a service that does not.
 */
@Component
class SalesforceCompositeClient(
    restClientBuilder: RestClient.Builder,
    private val auth: SalesforceAuth,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient = restClientBuilder
        .requestFactory(
            ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(Duration.ofSeconds(5))
                    .withReadTimeout(Duration.ofSeconds(30)),
            ),
        )
        .build()

    fun upsertFulfillment(events: List<FulfillmentEvent>): List<UpsertResult> =
        events.chunked(MAX_RECORDS_PER_CALL).flatMap { chunk -> upsertChunk(chunk, retryOnAuthFailure = true) }

    /**
     * Records the id the ERP assigned, against the Salesforce record id.
     *
     * This is what makes the return path possible. Fulfillment updates upsert on
     * `ERP_Order_Id__c`; until that field holds a value, no record matches and
     * Salesforce interprets the upsert as an insert — which fails on the required
     * AccountId with a message about selecting an account, some distance from the
     * actual cause.
     *
     * Updates by record id rather than upserting by external id, precisely because the
     * external id is what is missing at this point.
     */
    fun linkErpOrders(links: Map<String, String>): List<UpsertResult> {
        if (links.isEmpty()) return emptyList()

        return links.entries.chunked(MAX_RECORDS_PER_CALL).flatMap { chunk ->
            val session = auth.current()
            val body = mapOf(
                "allOrNone" to false,
                "records" to chunk.map { (salesforceOrderId, erpOrderId) ->
                    mapOf(
                        "attributes" to mapOf("type" to "Order"),
                        "id" to salesforceOrderId,
                        "ERP_Order_Id__c" to erpOrderId,
                    )
                },
            )

            val results = restClient.patch()
                .uri("${session.instanceUrl}/services/data/$API_VERSION/composite/sobjects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${session.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(object : ParameterizedTypeReference<List<UpsertResult>>() {})
                ?: emptyList()

            results.filterNot { it.success }.forEach { failure ->
                log.error(
                    "Could not record the ERP id against the Salesforce order: {}",
                    failure.errors.joinToString { "${it.statusCode} ${it.message}" },
                )
            }
            results
        }
    }

    private fun upsertChunk(events: List<FulfillmentEvent>, retryOnAuthFailure: Boolean): List<UpsertResult> {
        val session = auth.current()
        val body = mapOf(
            // allOrNone false: one bad record must not roll back the other 199. The
            // failures come back per-record and are handled individually.
            "allOrNone" to false,
            "records" to events.map { event ->
                mapOf(
                    "attributes" to mapOf("type" to "Order"),
                    "ERP_Order_Id__c" to event.erpOrderId,
                    "Fulfillment_Status__c" to event.fulfillmentStatus,
                )
            },
        )

        val results = try {
            restClient.patch()
                .uri("${session.instanceUrl}/services/data/$API_VERSION/composite/sobjects/Order/ERP_Order_Id__c")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${session.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus({ it.value() == 401 }) { _, _ ->
                    throw SalesforceAuthException("Access token rejected")
                }
                .onStatus({ it.value() == 429 || it.value() == 503 }) { _, _ ->
                    // Governor limit or maintenance. Both are worth retrying later,
                    // so this propagates and the container backs off.
                    throw SalesforceUnavailableException("Salesforce is throttling or unavailable")
                }
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    throw SalesforceRejectedException("Salesforce rejected the batch: ${response.statusCode}")
                }
                .body(object : ParameterizedTypeReference<List<UpsertResult>>() {})
                ?: emptyList()
        } catch (e: SalesforceAuthException) {
            // Tokens can be revoked at any time. Refresh once, then give up — a loop
            // here would hammer the token endpoint during a real outage.
            if (!retryOnAuthFailure) throw e
            log.warn("Salesforce rejected the token, refreshing and retrying once")
            auth.refresh()
            return upsertChunk(events, retryOnAuthFailure = false)
        }

        logFailures(events, results)
        return results
    }

    private fun logFailures(events: List<FulfillmentEvent>, results: List<UpsertResult>) {
        results.forEachIndexed { index, result ->
            if (!result.success) {
                val erpOrderId = events.getOrNull(index)?.erpOrderId ?: "unknown"
                log.error(
                    "Salesforce upsert failed for ERP order {}: {}",
                    erpOrderId,
                    result.errors.joinToString { "${it.statusCode} ${it.message}" },
                )
            }
        }
    }

    companion object {
        private const val API_VERSION = "v62.0"

        /** Salesforce's hard cap on the sObject Collections endpoint. */
        private const val MAX_RECORDS_PER_CALL = 200
    }
}
