-- The outbox is a transport buffer. What comes out of it must be byte-identical to what
-- went in, and jsonb does not do that: it parses the input and re-serializes it, which
-- reformats whitespace, reorders keys and silently discards duplicates. The message
-- published to Kafka was therefore never quite the message the service produced.
--
-- Harmless while every consumer parses the JSON, and immediately not harmless the moment
-- anything signs the payload, compares it byte-for-byte, or canonicalises it against a
-- schema. An integration_test caught it: an assertion on the exact payload failed
-- because Postgres had inserted a space after the colon.
ALTER TABLE outbox ALTER COLUMN payload TYPE TEXT USING payload::text;

-- Keep the validation jsonb was giving us for free. A malformed payload should still be
-- rejected at insert, where the bug is, rather than surfacing later as an undecodable
-- message in the DLQ. The cast is evaluated but not stored, so the original bytes survive.
ALTER TABLE outbox ADD CONSTRAINT outbox_payload_is_json CHECK (payload::jsonb IS NOT NULL);
