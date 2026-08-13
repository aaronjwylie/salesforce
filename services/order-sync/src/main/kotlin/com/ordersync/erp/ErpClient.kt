package com.ordersync.erp

import com.ordersync.config.ErpProperties
import com.ordersync.domain.OrderEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration

data class ErpOrderRequest(
    val orderNumber: String,
    val accountExternalId: String?,
    val status: String,
    val totalAmount: BigDecimal,
    val currencyCode: String,
) {
    companion object {
        fun from(event: OrderEvent) = ErpOrderRequest(
            orderNumber = event.orderNumber,
            accountExternalId = event.accountExternalId,
            // The ERP's vocabulary is not ours. Translating here keeps the canonical
            // event free of one downstream system's naming.
            status = when (event.status.name) {
                "ACTIVATED" -> "OPEN"
                "FULFILLED" -> "COMPLETE"
                "CANCELLED" -> "VOID"
                else -> event.status.name
            },
            totalAmount = event.totalAmount,
            currencyCode = event.currencyCode,
        )
    }
}

data class ErpOrderResponse(val erpOrderId: String)

/** The ERP rejected the payload. Retrying an identical request will fail identically. */
class ErpRejectedException(message: String) : RuntimeException(message)

/** The ERP is unwell. The same request may well succeed shortly. */
class ErpUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Component
class ErpClient(restClientBuilder: RestClient.Builder, properties: ErpProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Timeouts are set explicitly rather than inherited. A client with no read timeout
     * will hang a consumer thread indefinitely against an ERP that accepts the
     * connection and then goes quiet, which is a far more common failure than an
     * outright refusal.
     */
    private val restClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .requestFactory(
            ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
                    .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs)),
            ),
        )
        .build()

    fun upsertOrder(request: ErpOrderRequest): ErpOrderResponse {
        log.debug("Sending order {} to the ERP", request.orderNumber)

        return restClient.post()
            .uri("/api/orders")
            .body(request)
            .retrieve()
            // The distinction that matters: 4xx is our fault and will never succeed on
            // retry, so it goes straight to the DLQ. 5xx is theirs and is worth retrying.
            .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                throw ErpRejectedException(
                    "ERP rejected order ${request.orderNumber}: ${response.statusCode}",
                )
            }
            .onStatus(HttpStatusCode::is5xxServerError) { _, response ->
                throw ErpUnavailableException(
                    "ERP unavailable for order ${request.orderNumber}: ${response.statusCode}",
                )
            }
            .body(ErpOrderResponse::class.java)
            ?: throw ErpUnavailableException("ERP returned an empty body for order ${request.orderNumber}")
    }
}
