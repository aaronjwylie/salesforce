package com.ordersync.kafka

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

/**
 * A real broker for the tests that need one, started once for the whole suite.
 *
 * An object rather than a base class because the relay tests already extend
 * [com.ordersync.persistence.PostgresTestSupport] — they need both.
 */
object KafkaTestCluster {

    private val container: KafkaContainer =
        KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1")).also { it.start() }

    val bootstrapServers: String get() = container.bootstrapServers

    fun kafkaTemplate(): KafkaTemplate<String, String> =
        KafkaTemplate(
            DefaultKafkaProducerFactory(
                mapOf(
                    "bootstrap.servers" to bootstrapServers,
                    "key.serializer" to StringSerializer::class.java,
                    "value.serializer" to StringSerializer::class.java,
                    "acks" to "all",
                    "enable.idempotence" to true,
                ),
            ),
        )

    /** A fresh group each time, so every test reads its topic from the beginning. */
    fun consumerFor(vararg topics: String): Consumer<String, String> =
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).also { it.subscribe(topics.toList()) }

    /**
     * Polls until [count] records arrive or the deadline passes. Kafka's first poll
     * after subscribing usually returns nothing while the group rebalances, so a
     * single poll with a long timeout is not a substitute for this loop.
     */
    fun drain(consumer: Consumer<String, String>, count: Int, timeout: Duration = Duration.ofSeconds(20)):
        List<ConsumerRecord<String, String>> {
        val collected = mutableListOf<ConsumerRecord<String, String>>()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (collected.size < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach { collected += it }
        }
        return collected
    }
}
