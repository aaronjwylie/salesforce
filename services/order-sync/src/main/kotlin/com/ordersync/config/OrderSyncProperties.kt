package com.ordersync.config

import com.ordersync.domain.OrderChangeProcessor
import com.ordersync.domain.OrderEventPublisher
import com.ordersync.domain.ProcessedEventStore
import com.ordersync.domain.ReplayCheckpointStore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "ordersync.topics")
data class TopicProperties(
    val orders: String = "orders.v1",
    val ordersDlq: String = "orders.v1.DLQ",
    val fulfillment: String = "fulfillment.v1",
    val fulfillmentDlq: String = "fulfillment.v1.DLQ",
)

@ConfigurationProperties(prefix = "ordersync.erp")
data class ErpProperties(
    val baseUrl: String = "http://localhost:8081",
    val connectTimeoutMs: Long = 2_000,
    val readTimeoutMs: Long = 10_000,
)

@ConfigurationProperties(prefix = "ordersync.salesforce")
data class SalesforceProperties(
    val loginUrl: String = "https://login.salesforce.com",
    val clientId: String = "",
    val clientSecret: String = "",
    val orgId: String = "",
    val pubsubEndpoint: String = "api.pubsub.salesforce.com",
    val pubsubPort: Int = 7443,
    val pubsubTopic: String = "/event/Order_Change__e",
    /** How many events to request per Pub/Sub fetch. This is the backpressure knob. */
    val batchSize: Int = 25,
    val enabled: Boolean = false,
    /** Single-currency orgs do not expose CurrencyIsoCode, so reconciliation needs a default. */
    val defaultCurrency: String = "CAD",
)

@ConfigurationProperties(prefix = "ordersync.reconciliation")
data class ReconciliationProperties(
    val enabled: Boolean = true,
    /** Runs nightly by default; the stream is the normal path, this is the safety net. */
    val cron: String = "0 30 2 * * *",
    /**
     * How far back to sweep. Comfortably wider than Salesforce's 72 hour replay
     * retention, so a long outage cannot fall between the two mechanisms.
     */
    val lookbackHours: Long = 96,
)

@ConfigurationProperties(prefix = "ordersync.outbox")
data class OutboxProperties(
    val pollIntervalMs: Long = 500,
    val batchSize: Int = 100,
    val publishTimeoutMs: Long = 5_000,
    /** After this many failures a row is dead-lettered rather than blocking the queue. */
    val maxAttempts: Int = 5,
)

@Configuration
@EnableConfigurationProperties(
    TopicProperties::class,
    ErpProperties::class,
    SalesforceProperties::class,
    OutboxProperties::class,
    ReconciliationProperties::class,
)
class DomainConfig {

    /**
     * The domain is wired by hand rather than component-scanned, which is what keeps
     * `com.ordersync.domain` free of Spring annotations and unit-testable without a
     * container.
     */
    @Bean
    fun orderChangeProcessor(
        processedEvents: ProcessedEventStore,
        publisher: OrderEventPublisher,
        checkpoints: ReplayCheckpointStore,
    ) = OrderChangeProcessor(processedEvents, publisher, checkpoints)
}
