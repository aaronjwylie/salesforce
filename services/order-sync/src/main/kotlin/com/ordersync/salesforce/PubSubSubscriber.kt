package com.ordersync.salesforce

import com.google.protobuf.ByteString
import com.ordersync.application.OrderChangeService
import com.ordersync.config.SalesforceProperties
import com.ordersync.domain.ReplayCheckpointStore
import com.salesforce.eventbus.protobuf.FetchRequest
import com.salesforce.eventbus.protobuf.FetchResponse
import com.salesforce.eventbus.protobuf.ReplayPreset
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscribes to the Salesforce platform event stream.
 *
 * Two things make this different from a naive consumer loop:
 *
 * **Flow control.** The Pub/Sub API is pull-based. We ask for [SalesforceProperties.batchSize]
 * events and get at most that many; only after the batch has been committed do we ask
 * for more. A slow database therefore slows the subscription rather than building an
 * unbounded backlog in memory.
 *
 * **Resumption.** The first request replays from the stored checkpoint. Everything the
 * service missed while it was down arrives on reconnect, up to Salesforce's 72 hour
 * retention — past that, see [com.ordersync.reconciliation.OrderReconciliationJob].
 */
@Component
@ConditionalOnProperty(prefix = "ordersync.salesforce", name = ["enabled"], havingValue = "true")
class PubSubSubscriber(
    private val pubSub: PubSubClient,
    private val decoder: AvroEventDecoder,
    private val orderChanges: OrderChangeService,
    private val checkpoints: ReplayCheckpointStore,
    private val auth: SalesforceAuth,
    private val properties: SalesforceProperties,
    registry: MeterRegistry,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val reconnects: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pubsub-reconnect") }

    private var requests: StreamObserver<FetchRequest>? = null
    private var backoffSeconds = 1L

    private val received = registry.counter("ordersync.salesforce.events_received")
    private val decodeFailures = registry.counter("ordersync.salesforce.decode_failures")

    override fun start() {
        if (running.compareAndSet(false, true)) subscribe()
    }

    override fun stop() {
        running.set(false)
        runCatching { requests?.onCompleted() }
        reconnects.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()

    private fun subscribe() {
        if (!running.get()) return

        val resumeFrom = checkpoints.latest()
        log.info(
            "Subscribing to {} from {}",
            properties.pubsubTopic,
            resumeFrom?.let { "checkpoint $it" } ?: "the tip of the stream",
        )

        val stream = pubSub.asyncStub().subscribe(ResponseHandler())
        requests = stream

        val initial = FetchRequest.newBuilder()
            .setTopicName(properties.pubsubTopic)
            .setNumRequested(properties.batchSize)
            .apply {
                if (resumeFrom != null) {
                    replayPreset = ReplayPreset.CUSTOM
                    replayId = ByteString.copyFrom(resumeFrom.bytes)
                } else {
                    // LATEST, not EARLIEST: on a first run we do not want to replay
                    // however many days of history the org happens to be holding.
                    replayPreset = ReplayPreset.LATEST
                }
            }
            .build()

        stream.onNext(initial)
    }

    private inner class ResponseHandler : StreamObserver<FetchResponse> {

        override fun onNext(response: FetchResponse) {
            backoffSeconds = 1

            for (event in response.eventsList) {
                received.increment()
                try {
                    orderChanges.handle(decoder.decode(event))
                } catch (e: EventDecodingException) {
                    // Undecodable means the payload will never be readable. Log it,
                    // count it, and move on — blocking the stream helps nobody.
                    decodeFailures.increment()
                    log.error("Skipping undecodable event {}: {}", event.event.id, e.message)
                } catch (e: IllegalArgumentException) {
                    decodeFailures.increment()
                    log.error("Skipping unmappable event {}: {}", event.event.id, e.message)
                }
            }

            // Ask for the next batch only now, once this one is committed.
            requestMore(response.pendingNumRequested)
        }

        override fun onError(t: Throwable) {
            val status = (t as? StatusRuntimeException)?.status?.code

            if (status == Status.Code.UNAUTHENTICATED) {
                log.warn("Pub/Sub rejected our token; refreshing and reconnecting")
                runCatching { auth.refresh() }
            } else {
                log.error("Pub/Sub stream failed ({}): {}", status, t.message)
            }

            scheduleReconnect()
        }

        override fun onCompleted() {
            // Salesforce ends the stream periodically by design; it is not an error.
            log.info("Pub/Sub stream closed by the server, reconnecting")
            scheduleReconnect()
        }
    }

    private fun requestMore(pending: Int) {
        if (!running.get() || pending > 0) return
        runCatching {
            requests?.onNext(
                FetchRequest.newBuilder()
                    .setTopicName(properties.pubsubTopic)
                    .setNumRequested(properties.batchSize)
                    .build(),
            )
        }.onFailure { log.warn("Could not request more events: {}", it.message) }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = backoffSeconds
        backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
        log.info("Reconnecting to Pub/Sub in {}s", delay)
        runCatching { reconnects.schedule({ subscribe() }, delay, TimeUnit.SECONDS) }
    }

    companion object {
        private const val MAX_BACKOFF_SECONDS = 60L
    }
}
