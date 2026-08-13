package com.ordersync.salesforce

import com.ordersync.config.SalesforceProperties
import com.salesforce.eventbus.protobuf.PubSubGrpc
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Owns the gRPC channel to the Pub/Sub API.
 *
 * Every call carries three headers Salesforce requires: the access token, the org's
 * instance URL, and the org id as tenant id. They are attached by an interceptor that
 * reads the *current* session on each call, so a token refresh takes effect without
 * rebuilding the channel.
 */
@Component
class PubSubClient(
    private val auth: SalesforceAuth,
    private val properties: SalesforceProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val channel: ManagedChannel by lazy {
        log.info("Opening Pub/Sub channel to {}:{}", properties.pubsubEndpoint, properties.pubsubPort)
        ManagedChannelBuilder
            .forAddress(properties.pubsubEndpoint, properties.pubsubPort)
            .useTransportSecurity()
            // Pinned rather than left to defaults. A subscription is a long-lived
            // stream that can be idle for hours, and corporate proxies will silently
            // drop it; too aggressive a keepalive and Salesforce drops us instead.
            .keepAliveTime(60, TimeUnit.SECONDS)
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(MAX_INBOUND_BYTES)
            .build()
    }

    fun blockingStub(): PubSubGrpc.PubSubBlockingStub =
        PubSubGrpc.newBlockingStub(channel).withInterceptors(AuthInterceptor())

    fun asyncStub(): PubSubGrpc.PubSubStub =
        PubSubGrpc.newStub(channel).withInterceptors(AuthInterceptor())

    @PreDestroy
    fun close() {
        if (!channel.isShutdown) {
            log.info("Closing Pub/Sub channel")
            channel.shutdown()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow()
        }
    }

    private inner class AuthInterceptor : ClientInterceptor {
        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> =
            object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions),
            ) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    val session = auth.current()
                    headers.put(ACCESS_TOKEN, session.accessToken)
                    headers.put(INSTANCE_URL, session.instanceUrl)
                    headers.put(TENANT_ID, session.orgId)
                    super.start(responseListener, headers)
                }
            }
    }

    companion object {
        private const val MAX_INBOUND_BYTES = 8 * 1024 * 1024

        // Lower-case on purpose: gRPC metadata keys are case-sensitive here and
        // Salesforce rejects the conventional capitalised forms.
        private val ACCESS_TOKEN = Metadata.Key.of("accesstoken", Metadata.ASCII_STRING_MARSHALLER)
        private val INSTANCE_URL = Metadata.Key.of("instanceurl", Metadata.ASCII_STRING_MARSHALLER)
        private val TENANT_ID = Metadata.Key.of("tenantid", Metadata.ASCII_STRING_MARSHALLER)
    }
}
