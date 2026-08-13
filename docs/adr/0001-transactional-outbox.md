# ADR 0001 — Transactional outbox instead of dual-write

- **Status:** Accepted
- **Date:** 2026-01-15

## Context

Processing one Salesforce order change requires two writes: record the `EventUuid` so
we never process it twice, and publish an `OrderEvent` to Kafka. These hit two
different systems, and there is no distributed transaction spanning Postgres and Kafka.

Doing both naively — the dual-write — fails in both orders:

- **Insert then publish:** the process dies after the insert. The event is marked
  processed but never published. Redelivery from Salesforce is deduplicated away, and
  the order silently never reaches the ERP.
- **Publish then insert:** the process dies after the publish. Redelivery republishes,
  and the ERP sees a duplicate order.

The first failure mode is the dangerous one: it is silent, and the data is simply gone.
Nobody notices until a customer calls about an order the warehouse never saw.

## Decision

Write the dedupe record and the outgoing event to Postgres in a single local
transaction. A separate relay polls the `outbox` table and publishes to Kafka, deleting
rows only after the broker acknowledges.

```
BEGIN
  INSERT INTO processed_event (event_id) ...   -- unique index, fails on duplicate
  INSERT INTO outbox (topic, key, payload) ...
COMMIT
                    │
                    ▼
        OutboxRelay (polls, publishes, deletes on ack)
```

## Consequences

**Good**

- No lost events. If the transaction commits, the event *will* be published; if it
  rolls back, nothing was recorded and Salesforce's redelivery is a clean retry.
- The relay is restartable and needs no coordination: a crash mid-publish just means
  the row is still there next poll.
- Kafka being down degrades to lag rather than data loss.

**Bad**

- At-least-once, not exactly-once: a crash between publish and delete republishes the
  row. Downstream consumers must be idempotent. We accept this — the ERP client upserts
  by order number, so a duplicate is a no-op.
- Added latency equal to the poll interval (500ms). Acceptable; this is order sync,
  not a trading system.
- One more moving part to monitor. Mitigated by alerting on outbox depth and oldest-row
  age, both exported as Micrometer gauges.

## Alternatives considered

- **Kafka transactions with a Postgres-backed offset store.** Real exactly-once
  semantics, but it only covers Kafka-to-Kafka. Our source is a gRPC stream from
  Salesforce, so the transactional boundary does not reach far enough to help.
- **Debezium CDC on the outbox table.** Strictly better at scale and removes the polling
  latency, but it means running Kafka Connect. Revisit if throughput exceeds what
  polling comfortably handles; the outbox table shape is already Debezium-compatible so
  the migration is a configuration change, not a rewrite.
