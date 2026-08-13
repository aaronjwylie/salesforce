package com.ordersync.salesforce

import com.ordersync.domain.OrderStatus
import com.ordersync.domain.ReplayId
import com.ordersync.domain.SalesforceOrderChange
import com.salesforce.eventbus.protobuf.ConsumerEvent
import com.salesforce.eventbus.protobuf.SchemaRequest
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class EventDecodingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Turns an Avro-encoded platform event into a domain object.
 *
 * Schemas are fetched by id and cached forever. That is safe because the id *is* the
 * version: change a field on the platform event in Salesforce and every subsequent
 * event carries a new schema id, so a stale entry can never be applied to a payload it
 * does not describe.
 */
@Component
class AvroEventDecoder(private val pubSub: PubSubClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val schemas = ConcurrentHashMap<String, Schema>()

    fun decode(event: ConsumerEvent): SalesforceOrderChange {
        val schema = schemaFor(event.event.schemaId)
        val record = readRecord(event, schema)
        return toOrderChange(record, ReplayId.of(event.replayId.toByteArray()))
    }

    private fun schemaFor(schemaId: String): Schema = schemas.computeIfAbsent(schemaId) { id ->
        log.info("Fetching Avro schema {}", id)
        val info = pubSub.blockingStub().getSchema(SchemaRequest.newBuilder().setSchemaId(id).build())
        Schema.Parser().parse(info.schemaJson)
    }

    private fun readRecord(event: ConsumerEvent, schema: Schema): GenericRecord =
        try {
            val decoder = DecoderFactory.get().binaryDecoder(event.event.payload.toByteArray(), null)
            GenericDatumReader<GenericRecord>(schema).read(null, decoder)
        } catch (e: Exception) {
            throw EventDecodingException("Could not decode event ${event.event.id}", e)
        }

    private fun toOrderChange(record: GenericRecord, replayId: ReplayId) = SalesforceOrderChange(
        eventId = text(record, "Event_Uuid__c"),
        replayId = replayId,
        orderId = text(record, "Order_Id__c"),
        orderNumber = text(record, "Order_Number__c"),
        accountExternalId = text(record, "Account_External_Id__c"),
        status = statusOf(text(record, "Status__c")),
        totalAmount = amount(record, "Total_Amount__c"),
        currencyCode = text(record, "Currency_Code__c"),
        occurredAt = Instant.ofEpochMilli(record.get("Occurred_At__c") as Long),
    )

    /** Avro hands back Utf8, not String; `toString` is the documented way across. */
    private fun text(record: GenericRecord, field: String): String =
        record.get(field)?.toString()
            ?: throw EventDecodingException("Platform event is missing required field $field")

    /**
     * Salesforce Number fields arrive as Avro doubles. Going through the string form
     * avoids inheriting the binary rounding — `BigDecimal(2500.1)` is not 2500.1, and
     * these are order totals.
     */
    private fun amount(record: GenericRecord, field: String): BigDecimal =
        when (val raw = record.get(field)) {
            null -> BigDecimal.ZERO
            is Double -> BigDecimal.valueOf(raw)
            is java.nio.ByteBuffer -> BigDecimal(String(raw.array()))
            else -> BigDecimal(raw.toString())
        }

    /**
     * An unknown status is a data problem, not a transient one. Failing loudly sends it
     * to the DLQ rather than quietly mapping it to something plausible.
     */
    private fun statusOf(raw: String): OrderStatus =
        when (raw.uppercase()) {
            "DRAFT" -> OrderStatus.DRAFT
            "ACTIVATED" -> OrderStatus.ACTIVATED
            "FULFILLED" -> OrderStatus.FULFILLED
            "CANCELLED", "CANCELED" -> OrderStatus.CANCELLED
            else -> throw IllegalArgumentException("Unknown Salesforce order status '$raw'")
        }
}
