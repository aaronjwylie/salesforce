package com.ordersync.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.ordersync.config.TopicProperties
import com.ordersync.erp.ErpRejectedException
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries

@Configuration
class KafkaConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Retry policy for every listener in the service.
     *
     * The important part is not the backoff, it is the classification. A malformed
     * payload or an order the ERP refuses will fail identically on every attempt;
     * retrying it four times just delays the inevitable and holds up the partition
     * behind it. Those go straight to the DLQ. Only genuinely transient failures —
     * the ERP being down, a network blip — are worth waiting on.
     */
    @Bean
    fun errorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
        topics: TopicProperties,
        registry: MeterRegistry,
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, exception ->
            val dlq = dlqFor(record.topic(), topics)
            log.error(
                "Dead-lettering {} from {} to {}: {}",
                record.key(), record.topic(), dlq, exception.message,
            )
            registry.counter("ordersync.consumer.dead_lettered", "topic", record.topic()).increment()
            // Partition -1 lets the producer choose, so the DLQ does not have to have
            // the same partition count as the source topic.
            TopicPartition(dlq, -1)
        }

        val backOff = ExponentialBackOffWithMaxRetries(4).apply {
            initialInterval = 500
            multiplier = 2.0
            maxInterval = 10_000
        }

        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(
                ErpRejectedException::class.java,
                JsonProcessingException::class.java,
                IllegalArgumentException::class.java,
            )
            setRetryListeners({ record, ex, attempt ->
                log.warn(
                    "Retry {} for {} on {}: {}",
                    attempt, record.key(), record.topic(), ex.message,
                )
            })
        }
    }

    /**
     * A second container factory, for listeners that want the whole poll at once.
     *
     * Set as a distinct bean rather than flipping `spring.kafka.listener.type=batch`
     * globally: the ERP-bound listener genuinely wants one message at a time, because
     * its failure handling is per-order. Only the Salesforce-bound one batches, and it
     * does so to conserve API calls.
     */
    @Bean
    fun batchListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        errorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            this.consumerFactory = consumerFactory
            isBatchListener = true
            setCommonErrorHandler(errorHandler)
        }

    private fun dlqFor(topic: String, topics: TopicProperties): String = when (topic) {
        topics.orders -> topics.ordersDlq
        topics.fulfillment -> topics.fulfillmentDlq
        else -> "$topic.DLQ"
    }
}
