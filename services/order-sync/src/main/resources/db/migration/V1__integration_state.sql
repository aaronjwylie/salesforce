-- Idempotency ledger. The primary key is the whole point: the check-and-insert is a
-- single atomic statement, so two instances racing on the same redelivered event
-- cannot both decide they saw it first.
CREATE TABLE processed_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    order_number VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Rows are only useful until Salesforce can no longer redeliver them. A retention job
-- prunes on this index rather than scanning the table.
CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);

-- Transactional outbox. Written in the same transaction as processed_event, drained
-- by OutboxRelay. See docs/adr/0001-transactional-outbox.md.
CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    topic        VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- The relay reads oldest-first, which the primary key index already serves, so no
-- extra index here. Deliberate: every index is write amplification on a hot table.

-- One row per subscribed stream. Lets a single service own several Salesforce
-- channels later without a schema change.
-- replay_id is text, not a number: the Pub/Sub API declares it as opaque bytes, and we
-- store the base64 form so it survives round-tripping without a bytea escape dance.
CREATE TABLE replay_checkpoint (
    stream_name VARCHAR(128) PRIMARY KEY,
    replay_id   VARCHAR(512) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
