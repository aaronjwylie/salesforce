package com.ordersync.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.ordersync.config.TopicProperties
import com.ordersync.domain.FulfillmentEvent
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class FulfillmentWebhookRequest(
    @field:NotBlank val erpOrderId: String,
    @field:NotBlank val orderNumber: String,
    @field:NotBlank val status: String,
    val occurredAt: Instant? = null,
)

/**
 * Where the ERP tells us an order moved.
 *
 * The handler does nothing but validate and enqueue. Calling Salesforce inline would
 * tie the ERP's webhook latency — and its retry behaviour — to Salesforce being up,
 * which is exactly the coupling this service exists to remove. Kafka absorbs the
 * spike; [com.ordersync.kafka.FulfillmentConsumer] does the slow part.
 */
@RestController
@RequestMapping("/webhooks/erp")
class FulfillmentWebhookController(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val topics: TopicProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/fulfillment")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun onFulfillment(@Valid @RequestBody request: FulfillmentWebhookRequest) {
        val event = FulfillmentEvent(
            eventId = UUID.randomUUID().toString(),
            erpOrderId = request.erpOrderId,
            orderNumber = request.orderNumber,
            fulfillmentStatus = request.status,
            occurredAt = request.occurredAt ?: Instant.now(),
        )

        kafkaTemplate.send(
            topics.fulfillment,
            // Key by ERP order id so updates for one order stay ordered across partitions.
            event.erpOrderId,
            objectMapper.writeValueAsString(event),
        )

        log.debug("Accepted fulfillment update {} for {}", request.status, request.erpOrderId)
    }
}
