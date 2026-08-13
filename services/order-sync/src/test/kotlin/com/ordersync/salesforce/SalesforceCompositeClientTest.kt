package com.ordersync.salesforce

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.ordersync.domain.FulfillmentEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class SalesforceCompositeClientTest {

    companion object {
        private val wiremock = WireMockServer(options().dynamicPort())

        @BeforeAll
        @JvmStatic
        fun startStub() = wiremock.start()

        @AfterAll
        @JvmStatic
        fun stopStub() = wiremock.stop()
    }

    private lateinit var auth: SalesforceAuth
    private lateinit var client: SalesforceCompositeClient

    private val upsertPath = "/services/data/v62.0/composite/sobjects/Order/ERP_Order_Id__c"

    @BeforeEach
    fun setUp() {
        wiremock.resetAll()
        auth = mockk(relaxed = true)
        every { auth.current() } returns SalesforceSession(
            accessToken = "token-1",
            instanceUrl = "http://localhost:${wiremock.port()}",
            orgId = "00Dxx0000000000EAA",
        )
        client = SalesforceCompositeClient(RestClient.builder(), auth)
    }

    private fun event(erpOrderId: String, status: String = "Shipped") = FulfillmentEvent(
        eventId = "evt-$erpOrderId",
        erpOrderId = erpOrderId,
        orderNumber = "SO-1001",
        fulfillmentStatus = status,
        occurredAt = Instant.parse("2026-01-15T10:00:00Z"),
    )

    private fun stubSuccess(count: Int) {
        val body = (1..count).joinToString(",", "[", "]") {
            """{"id":"801000000000$it","success":true,"created":false,"errors":[]}"""
        }
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body),
            ),
        )
    }

    @Test
    fun `upserts on the external id, not the Salesforce id`() {
        stubSuccess(1)

        client.upsertFulfillment(listOf(event("ERP-8891")))

        wiremock.verify(
            patchRequestedFor(urlPathMatching(".*$upsertPath"))
                .withRequestBody(matchingJsonPath("$.records[0].ERP_Order_Id__c", com.github.tomakehurst.wiremock.client.WireMock.equalTo("ERP-8891")))
                .withRequestBody(matchingJsonPath("$.records[0].Fulfillment_Status__c", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Shipped"))),
        )
    }

    /** One bad record must not roll back the other 199. */
    @Test
    fun `sends allOrNone false`() {
        stubSuccess(1)

        client.upsertFulfillment(listOf(event("ERP-8891")))

        wiremock.verify(
            patchRequestedFor(urlPathMatching(".*$upsertPath"))
                .withRequestBody(matchingJsonPath("$.allOrNone", com.github.tomakehurst.wiremock.client.WireMock.equalTo("false"))),
        )
    }

    /**
     * The whole reason this endpoint is used: 200 orders must cost one API call, not
     * 200, or a Developer Edition org's daily allocation is gone by lunchtime.
     */
    @Test
    fun `sends one call for a full batch`() {
        stubSuccess(200)

        client.upsertFulfillment((1..200).map { event("ERP-$it") })

        wiremock.verify(1, patchRequestedFor(urlPathMatching(".*$upsertPath")))
    }

    @Test
    fun `splits anything over the two hundred record cap`() {
        stubSuccess(200)

        client.upsertFulfillment((1..250).map { event("ERP-$it") })

        wiremock.verify(2, patchRequestedFor(urlPathMatching(".*$upsertPath")))
    }

    @Test
    fun `refreshes the token once when Salesforce rejects it`() {
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*"))
                .inScenario("expired token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("refreshed"),
        )
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*"))
                .inScenario("expired token")
                .whenScenarioStateIs("refreshed")
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""[{"id":"801","success":true,"created":false,"errors":[]}]"""),
                ),
        )

        val results = client.upsertFulfillment(listOf(event("ERP-8891")))

        verify(exactly = 1) { auth.refresh() }
        results shouldHaveSize 1
        results[0].success shouldBe true
    }

    /** A refresh loop during a real outage would hammer the token endpoint. */
    @Test
    fun `gives up after one failed refresh`() {
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*"))
                .willReturn(aResponse().withStatus(401)),
        )

        shouldThrow<SalesforceAuthException> {
            client.upsertFulfillment(listOf(event("ERP-8891")))
        }

        verify(exactly = 1) { auth.refresh() }
    }

    @Test
    fun `treats a governor limit as retryable`() {
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*"))
                .willReturn(aResponse().withStatus(429)),
        )

        shouldThrow<SalesforceUnavailableException> {
            client.upsertFulfillment(listOf(event("ERP-8891")))
        }
    }

    @Test
    fun `treats a bad request as permanent`() {
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*"))
                .willReturn(aResponse().withStatus(400)),
        )

        shouldThrow<SalesforceRejectedException> {
            client.upsertFulfillment(listOf(event("ERP-8891")))
        }
    }

    @Test
    fun `reports per-record failures without failing the batch`() {
        wiremock.stubFor(
            patch(urlPathMatching(".*/composite/sobjects/Order/.*")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        [
                          {"id":"801","success":true,"created":false,"errors":[]},
                          {"success":false,"created":false,"errors":[
                            {"statusCode":"ENTITY_IS_DELETED","message":"entity is deleted","fields":[]}
                          ]}
                        ]
                        """.trimIndent(),
                    ),
            ),
        )

        val results = client.upsertFulfillment(listOf(event("ERP-1"), event("ERP-2")))

        results shouldHaveSize 2
        results[0].success shouldBe true
        results[1].success shouldBe false
        results[1].errors[0].statusCode shouldBe "ENTITY_IS_DELETED"
    }

    @Test
    fun `does nothing when there is nothing to send`() {
        client.upsertFulfillment(emptyList()) shouldHaveSize 0

        wiremock.verify(0, patchRequestedFor(urlPathMatching(".*$upsertPath")))
    }
}
