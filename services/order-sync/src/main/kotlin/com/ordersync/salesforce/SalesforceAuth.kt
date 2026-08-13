package com.ordersync.salesforce

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.ordersync.config.SalesforceProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

data class SalesforceSession(
    val accessToken: String,
    val instanceUrl: String,
    val orgId: String,
)

/**
 * The OAuth token endpoint response. Only the fields we actually use are mapped;
 * `issued_at` and `signature` are ignored rather than modelled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("instance_url") val instanceUrl: String,
    /** Identity URL, e.g. `https://login.salesforce.com/id/{orgId}/{userId}`. */
    @JsonProperty("id") val id: String? = null,
)

class SalesforceAuthException(message: String) : RuntimeException(message)

/**
 * OAuth 2.0 client credentials against the org's token endpoint.
 *
 * Access tokens are not refreshed on a timer. Salesforce does not tell you how long
 * one is good for in any way worth trusting, and a token can be invalidated early by
 * an admin. The gRPC stream instead reacts to an UNAUTHENTICATED response by calling
 * [refresh] and reconnecting, which is the only signal that is actually reliable.
 */
@Component
class SalesforceAuth(
    restClientBuilder: RestClient.Builder,
    private val properties: SalesforceProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = restClientBuilder.build()

    @Volatile
    private var session: SalesforceSession? = null

    fun current(): SalesforceSession = session ?: refresh()

    @Synchronized
    fun refresh(): SalesforceSession {
        // Another thread may have refreshed while this one waited on the lock.
        session?.let { existing ->
            if (existing !== session) return existing
        }

        require(properties.clientId.isNotBlank()) { "ordersync.salesforce.client-id is not set" }

        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
        }

        val response = restClient.post()
            .uri("${properties.loginUrl}/services/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse::class.java)
            ?: throw SalesforceAuthException("Empty token response from ${properties.loginUrl}")

        val refreshed = SalesforceSession(
            accessToken = response.accessToken,
            instanceUrl = response.instanceUrl,
            orgId = properties.orgId.ifBlank { orgIdFrom(response.id) },
        )

        log.info("Authenticated to Salesforce org {} at {}", refreshed.orgId, refreshed.instanceUrl)
        session = refreshed
        return refreshed
    }

    /** The identity URL ends `/id/{orgId}/{userId}`; the Pub/Sub API wants that org id as its tenant id. */
    private fun orgIdFrom(identityUrl: String?): String =
        identityUrl?.split("/")?.dropLast(1)?.lastOrNull()
            ?: throw SalesforceAuthException(
                "Could not derive the org id from the token response. " +
                    "Set ordersync.salesforce.org-id explicitly.",
            )
}
