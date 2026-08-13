package com.ordersync.salesforce

import com.google.protobuf.ByteString
import com.ordersync.domain.OrderStatus
import com.salesforce.eventbus.protobuf.ConsumerEvent
import com.salesforce.eventbus.protobuf.ProducerEvent
import com.salesforce.eventbus.protobuf.SchemaInfo
import com.salesforce.eventbus.protobuf.SchemaRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.EncoderFactory
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant

/**
 * Decodes real Avro bytes against a real schema — only the gRPC transport is faked.
 * Encoding a record and reading it back is the only way to catch a field name or type
 * that does not survive the round trip, which is exactly the failure that otherwise
 * waits until a live org is connected.
 */
class AvroEventDecoderTest {

    private val schemaJson = """
        {
          "type": "record",
          "name": "Order_Change__e",
          "fields": [
            {"name": "Event_Uuid__c", "type": "string"},
            {"name": "Order_Id__c", "type": "string"},
            {"name": "Order_Number__c", "type": "string"},
            {"name": "Account_External_Id__c", "type": "string"},
            {"name": "Status__c", "type": "string"},
            {"name": "Total_Amount__c", "type": "double"},
            {"name": "Currency_Code__c", "type": "string"},
            {"name": "Occurred_At__c", "type": "long"}
          ]
        }
    """.trimIndent()

    private val schema = Schema.Parser().parse(schemaJson)
    private val pubSub = mockk<PubSubClient>()
    private val decoder = AvroEventDecoder(pubSub)

    private fun stubSchemaLookup(schemaId: String = "schema-1") {
        val stub = mockk<com.salesforce.eventbus.protobuf.PubSubGrpc.PubSubBlockingStub>()
        every { pubSub.blockingStub() } returns stub
        every { stub.getSchema(any<SchemaRequest>()) } returns
            SchemaInfo.newBuilder().setSchemaId(schemaId).setSchemaJson(schemaJson).build()
    }

    private fun anEvent(
        status: String = "Activated",
        total: Double = 2500.00,
        schemaId: String = "schema-1",
        replayId: ByteArray = byteArrayOf(0, 1, 2, 3),
    ): ConsumerEvent {
        val record: GenericRecord = GenericData.Record(schema).apply {
            put("Event_Uuid__c", "a3f9e1c2-0000-4000-8000-000000000001")
            put("Order_Id__c", "8010X00000AbCdEQAV")
            put("Order_Number__c", "SO-1042")
            put("Account_External_Id__c", "ACCT-42")
            put("Status__c", status)
            put("Total_Amount__c", total)
            put("Currency_Code__c", "CAD")
            put("Occurred_At__c", Instant.parse("2026-01-15T10:00:00Z").toEpochMilli())
        }

        val bytes = ByteArrayOutputStream().use { out ->
            val encoder = EncoderFactory.get().binaryEncoder(out, null)
            GenericDatumWriter<GenericRecord>(schema).write(record, encoder)
            encoder.flush()
            out.toByteArray()
        }

        return ConsumerEvent.newBuilder()
            .setEvent(
                ProducerEvent.newBuilder()
                    .setId("evt-1")
                    .setSchemaId(schemaId)
                    .setPayload(ByteString.copyFrom(bytes)),
            )
            .setReplayId(ByteString.copyFrom(replayId))
            .build()
    }

    @Test
    fun `decodes a platform event into a domain change`() {
        stubSchemaLookup()

        val change = decoder.decode(anEvent())

        change.eventId shouldBe "a3f9e1c2-0000-4000-8000-000000000001"
        change.orderNumber shouldBe "SO-1042"
        change.accountExternalId shouldBe "ACCT-42"
        change.status shouldBe OrderStatus.ACTIVATED
        change.currencyCode shouldBe "CAD"
        change.occurredAt shouldBe Instant.parse("2026-01-15T10:00:00Z")
    }

    @Test
    fun `carries the replay id through as opaque bytes`() {
        stubSchemaLookup()

        val change = decoder.decode(anEvent(replayId = byteArrayOf(0, -17, -1, 42)))

        change.replayId!!.bytes.toList() shouldBe listOf<Byte>(0, -17, -1, 42)
    }

    /** Avro doubles do not represent 2500.10 exactly; going via the string form does. */
    @Test
    fun `does not inherit binary rounding on order totals`() {
        stubSchemaLookup()

        val change = decoder.decode(anEvent(total = 2500.10))

        change.totalAmount.compareTo(BigDecimal("2500.10")) shouldBe 0
    }

    @Test
    fun `fetches each schema once and caches it`() {
        stubSchemaLookup()

        decoder.decode(anEvent())
        decoder.decode(anEvent())
        decoder.decode(anEvent())

        verify(exactly = 1) { pubSub.blockingStub() }
    }

    /**
     * An unmapped status is bad data, not a blip. It must fail loudly so the event is
     * dead-lettered rather than quietly becoming something plausible.
     */
    @Test
    fun `refuses a status it does not recognise`() {
        stubSchemaLookup()

        shouldThrow<IllegalArgumentException> {
            decoder.decode(anEvent(status = "Teleported"))
        }
    }

    @Test
    fun `accepts either spelling of cancelled`() {
        stubSchemaLookup()

        decoder.decode(anEvent(status = "Canceled")).status shouldBe OrderStatus.CANCELLED
    }

    @Test
    fun `fails cleanly when the payload does not match the schema`() {
        stubSchemaLookup()
        val corrupt = ConsumerEvent.newBuilder()
            .setEvent(
                ProducerEvent.newBuilder()
                    .setId("evt-bad")
                    .setSchemaId("schema-1")
                    .setPayload(ByteString.copyFrom(byteArrayOf(9, 9, 9, 9))),
            )
            .setReplayId(ByteString.copyFrom(byteArrayOf(1)))
            .build()

        shouldThrow<EventDecodingException> { decoder.decode(corrupt) }
    }
}
