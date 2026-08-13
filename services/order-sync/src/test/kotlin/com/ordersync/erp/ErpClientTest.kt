package com.ordersync.erp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.ordersync.config.ErpProperties
import com.ordersync.domain.OrderEvent
import com.ordersync.domain.OrderStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant

/**
 * Contract tests against a stubbed ERP. What matters here is not that the HTTP call
 * happens — it is that failures are classified correctly, because that classification
 * decides whether a message is retried or dead-lettered.
 */
class ErpClientTest {

    companion object {
        private val wiremock = WireMockServer(options().dynamicPort())

        @BeforeAll
        @JvmStatic
        fun startStub() = wiremock.start()

        @AfterAll
        @JvmStatic
        fun stopStub() = wiremock.stop()
    }

    private lateinit var client: ErpClient

    @BeforeEach
    fun setUp() {
        wiremock.resetAll()
        client = ErpClient(
            RestClient.builder(),
            ErpProperties(baseUrl = "http://localhost:${wiremock.port()}"),
        )
    }

    private fun anOrderEvent(status: OrderStatus = OrderStatus.ACTIVATED) = OrderEvent(
        eventId = "evt-1",
        orderNumber = "SO-1001",
        accountExternalId = "ACCT-42",
        status = status,
        totalAmount = BigDecimal("2500.00"),
        currencyCode = "CAD",
        occurredAt = Instant.parse("2026-01-15T10:00:00Z"),
    )

    @Test
    fun `returns the id the ERP assigned`() {
        wiremock.stubFor(
            post(urlEqualTo("/api/orders")).willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"erpOrderId":"ERP-8891"}"""),
            ),
        )

        val response = client.upsertOrder(ErpOrderRequest.from(anOrderEvent()))

        response.erpOrderId shouldBe "ERP-8891"
    }

    @Test
    fun `sends the ERP its own vocabulary, not Salesforce's`() {
        wiremock.stubFor(
            post(urlEqualTo("/api/orders")).willReturn(
                aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"erpOrderId":"ERP-8891"}"""),
            ),
        )

        client.upsertOrder(ErpOrderRequest.from(anOrderEvent(OrderStatus.ACTIVATED)))

        wiremock.verify(
            postRequestedFor(urlEqualTo("/api/orders")).withRequestBody(
                equalToJson(
                    """
                    {
                      "orderNumber": "SO-1001",
                      "accountExternalId": "ACCT-42",
                      "status": "OPEN",
                      "totalAmount": 2500.00,
                      "currencyCode": "CAD"
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /**
     * A 4xx means the payload is wrong. Retrying it four times with backoff just delays
     * the DLQ and blocks the partition behind it, so this exception is on the
     * not-retryable list in KafkaConfig.
     */
    @Test
    fun `treats a rejection as permanent`() {
        wiremock.stubFor(
            post(urlEqualTo("/api/orders")).willReturn(aResponse().withStatus(400)),
        )

        val error = shouldThrow<ErpRejectedException> {
            client.upsertOrder(ErpOrderRequest.from(anOrderEvent()))
        }

        error.message!! shouldContain "SO-1001"
    }

    @Test
    fun `treats an outage as worth retrying`() {
        wiremock.stubFor(
            post(urlEqualTo("/api/orders")).willReturn(aResponse().withStatus(503)),
        )

        shouldThrow<ErpUnavailableException> {
            client.upsertOrder(ErpOrderRequest.from(anOrderEvent()))
        }
    }

    @Test
    fun `treats a conflict as permanent too`() {
        wiremock.stubFor(
            post(urlEqualTo("/api/orders")).willReturn(aResponse().withStatus(409)),
        )

        shouldThrow<ErpRejectedException> {
            client.upsertOrder(ErpOrderRequest.from(anOrderEvent()))
        }
    }
}
