# sf-order-sync — architecture

## Problem

Salesforce is the system of record for Orders. A downstream ERP (Oracle EBS, here
represented by `mock-erp`) owns fulfillment. Neither system can call the other
directly: Salesforce has hard API governor limits and no retry semantics worth
relying on, and the ERP is only reachable inside the corporate network.

`order-sync` brokers both directions over Kafka.

## Context

```
┌──────────────┐   Pub/Sub API    ┌──────────────┐   orders.v1    ┌──────────┐
│  Salesforce  │ ───(gRPC)──────► │              │ ─────────────► │          │
│              │                  │  order-sync  │                │  Kafka   │
│  Order,      │ ◄──Composite ─── │              │ ◄───────────── │          │
│  Order_      │      API         └──────────────┘ fulfillment.v1 └──────────┘
│  Change__e   │                         │                              ▲
└──────────────┘                         │ REST                         │
                                         ▼                              │
                                  ┌──────────────┐   webhook            │
                                  │   ERP        │ ─────────────────────┘
                                  │ (Oracle EBS) │
                                  └──────────────┘
```

## Flow A — Salesforce to ERP

1. Subscribe to the Salesforce **Pub/Sub API** for `/event/Order_Change__e`, resuming
   from the persisted replay id.
2. `OrderChangeProcessor` deduplicates on `EventUuid`, drops non-ERP-relevant statuses,
   and translates the Salesforce payload into a canonical `OrderEvent`.
3. The event is written to the **transactional outbox** in the same transaction as the
   dedupe record, then relayed to `orders.v1`.
4. A consumer maps the canonical event onto the ERP's REST contract and POSTs it.

## Flow B — ERP to Salesforce

1. The ERP posts fulfillment updates to a webhook endpoint, which lands them on
   `fulfillment.v1`.
2. A consumer batches updates and upserts them into Salesforce via the Composite API,
   keyed on `External_Id__c` so no Salesforce ids need to leak into the ERP.

## The parts that are actually load-bearing

| Concern | Approach |
| --- | --- |
| At-least-once delivery from Salesforce | Dedupe on `EventUuid` with a unique index; check-and-insert is one atomic statement |
| Restart without event loss | Replay id checkpointed to Postgres, advanced on **every** event including skipped ones |
| DB write and Kafka publish diverging | Transactional outbox + relay — see [ADR 0001](adr/0001-transactional-outbox.md) |
| Poison messages | Retry with exponential backoff, then `orders.v1.DLQ`; a replay endpoint drains it |
| Salesforce API limits | Composite API batching, circuit breaker on HTTP 429, bulk paths for backfill |
| Ordering | Kafka partition key is `orderNumber`, so one order's events stay ordered |
| Replay ids expiring after 72h | Nightly reconciliation re-derives order state from SOQL and feeds it through the same processor; deduplication absorbs the overlap |
| Reconciled orders corrupting the checkpoint | `replayId` is nullable — a change that never came from the stream has no position, and the processor leaves the checkpoint alone |

## Test strategy

Two Gradle lanes: `test` needs nothing but a JVM, `integrationTest` needs Docker.
That split is deliberate — the fast lane has to stay fast enough to run on every save.

| Layer | Tooling | What it proves | Lane |
| --- | --- | --- | --- |
| Acceptance | Cucumber, in-memory adapters | Business rules, in milliseconds | fast |
| Contract | WireMock | ERP and Salesforce REST behaviour: 4xx vs 5xx classification, 401 refresh, 429 backoff, 200-record batching | fast |
| Decoding | Real Avro encode/decode, mocked gRPC | Field mapping survives the round trip | fast |
| Integration | Testcontainers (Postgres, Kafka) | `ON CONFLICT`, `SKIP LOCKED`, relay behaviour against real infrastructure | docker |

Acceptance tests are written **before** the code they describe. The git history is the
evidence: each feature lands as a red commit followed by a green one.

### What is not tested

The live Pub/Sub subscription — `PubSubSubscriber`, the gRPC channel, and the auth
interceptor — has no automated coverage. It compiles against Salesforce's published
proto and the Avro decoding it feeds is tested, but the streaming path itself has only
ever run against a mock. Until it has pulled one real event from a real org, treat it
as unverified.

## Decisions

| | |
| --- | --- |
| [0001](adr/0001-transactional-outbox.md) | Transactional outbox instead of dual-write |
| [0002](adr/0002-pubsub-api-over-cometd.md) | Pub/Sub API instead of the CometD streaming API |
| [0003](adr/0003-replay-ids-are-opaque.md) | Replay ids are opaque, and the checkpoint reflects that |
| [0004](adr/0004-reconciliation-over-replay-expiry.md) | Reconciliation is required, not optional |

## Deliberately out of scope

- Multi-org Salesforce support
- Exactly-once end-to-end delivery. We do at-least-once plus idempotent consumers,
  which is the honest guarantee.
