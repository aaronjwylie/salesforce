package com.ordersync.acceptance

import com.ordersync.domain.OrderChangeProcessor
import com.ordersync.domain.OrderStatus
import com.ordersync.domain.ReplayId
import com.ordersync.domain.SalesforceOrderChange
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OrderForwardingSteps {

    private lateinit var processedEvents: InMemoryProcessedEventStore
    private lateinit var publisher: RecordingOrderEventPublisher
    private lateinit var checkpoints: InMemoryReplayCheckpointStore
    private lateinit var processor: OrderChangeProcessor

    /** The most recent change, so "redelivers that event" can replay it verbatim. */
    private var lastChange: SalesforceOrderChange? = null

    @Before
    fun setUp() {
        processedEvents = InMemoryProcessedEventStore()
        publisher = RecordingOrderEventPublisher()
        checkpoints = InMemoryReplayCheckpointStore()
        processor = OrderChangeProcessor(processedEvents, publisher, checkpoints)
        lastChange = null
    }

    @Given("no order events have been published")
    fun noOrderEventsHaveBeenPublished() {
        publisher.published.shouldHaveSize(0)
        checkpoints.latest().shouldBeNull()
    }

    @Given("Salesforce published order {string} with status {string} at stream position {string}")
    @When("Salesforce publishes order {string} with status {string} at stream position {string}")
    fun salesforcePublishesOrder(orderNumber: String, status: String, position: String) {
        publish(orderNumber, OrderStatus.valueOf(status), position, BigDecimal("100.00"), "CAD")
    }

    @When("Salesforce publishes order {string} for {bigdecimal} {word} with status {string} at stream position {string}")
    fun salesforcePublishesOrderWithAmount(
        orderNumber: String,
        amount: BigDecimal,
        currency: String,
        status: String,
        position: String,
    ) {
        publish(orderNumber, OrderStatus.valueOf(status), position, amount, currency)
    }

    @When("reconciliation recovers order {string} with status {string}")
    fun reconciliationRecoversOrder(orderNumber: String, status: String) {
        processor.process(
            change(orderNumber, OrderStatus.valueOf(status), null, BigDecimal("100.00"), "CAD"),
        )
    }

    @When("Salesforce redelivers that event")
    fun salesforceRedeliversThatEvent() {
        val change = requireNotNull(lastChange) { "no event has been published yet" }
        processor.process(change)
    }

    @Then("order {string} is published to the ERP topic")
    fun orderIsPublished(orderNumber: String) {
        publisher.published.map { it.orderNumber } shouldBe listOf(orderNumber)
    }

    @Then("order {string} is published to the ERP topic exactly once")
    fun orderIsPublishedExactlyOnce(orderNumber: String) {
        publisher.published.filter { it.orderNumber == orderNumber } shouldHaveSize 1
    }

    @Then("no order is published to the ERP topic")
    fun noOrderIsPublished() {
        publisher.published.shouldHaveSize(0)
    }

    @Then("the stream position is remembered as {string}")
    fun theStreamPositionIs(position: String) {
        checkpoints.latest() shouldBe replayIdFor(position)
    }

    @Then("the published order carries {bigdecimal} {word}")
    fun thePublishedOrderCarries(amount: BigDecimal, currency: String) {
        val event = publisher.published.single()
        // compareTo, not equals: BigDecimal("2500.00") != BigDecimal("2500.0")
        (event.totalAmount.compareTo(amount) == 0) shouldBe true
        event.currencyCode shouldBe currency
    }

    /**
     * Scenarios name positions with readable labels rather than real replay ids, which
     * are opaque binary. The mapping is arbitrary and only has to be consistent.
     */
    private fun replayIdFor(position: String) = ReplayId.of(position.toByteArray())

    private fun publish(
        orderNumber: String,
        status: OrderStatus,
        position: String,
        amount: BigDecimal,
        currency: String,
    ) {
        val change = change(orderNumber, status, position, amount, currency)
        lastChange = change
        processor.process(change)
    }

    /** A null [position] models a change that came from reconciliation, not the stream. */
    private fun change(
        orderNumber: String,
        status: OrderStatus,
        position: String?,
        amount: BigDecimal,
        currency: String,
    ) = SalesforceOrderChange(
        eventId = UUID.randomUUID().toString(),
        replayId = position?.let { replayIdFor(it) },
        orderId = "801000000000${position ?: "recon"}AAA",
        orderNumber = orderNumber,
        accountExternalId = "ACCT-42",
        status = status,
        totalAmount = amount,
        currencyCode = currency,
        occurredAt = Instant.parse("2026-01-15T10:00:00Z"),
    )
}
