# ADR 0002 — Salesforce Pub/Sub API instead of the CometD streaming API

- **Status:** Accepted
- **Date:** 2026-01-15

## Context

We need to consume `Order_Change__e` platform events from Salesforce. Salesforce offers
two subscription mechanisms:

1. **Streaming API (CometD)** — long-polling over HTTP with Bayeux, the older option,
   with mature Java client libraries.
2. **Pub/Sub API** — gRPC with Avro-encoded payloads, generally available since
   Winter '22, and the direction Salesforce is investing in.

Change Data Capture and Platform Events are both exposed through either.

## Decision

Use the Pub/Sub API.

## Rationale

- **Flow control.** Pub/Sub is pull-based: the client requests *n* events and gets at
  most *n*. CometD pushes, so a slow consumer builds a backlog it cannot signal, and
  Salesforce eventually disconnects it. Our consumer does database work per event, so
  backpressure is not optional.
- **Event allocation.** Pub/Sub counts delivered events against the daily allocation;
  CometD counts *delivered per subscriber*. With multiple service instances that
  difference is significant.
- **Schema handling.** Avro payloads come with a retrievable schema id, so we can
  detect a platform event field change at deserialize time rather than discovering it
  through a null in production.
- **Replay semantics are the same in both**, so this decision does not affect the
  checkpointing design in [ADR 0001](0001-transactional-outbox.md).

## Consequences

**Good**

- Real backpressure, so an instance under load slows down instead of falling off the
  stream.
- One protocol for platform events, CDC, and change events, rather than three clients.

**Bad**

- More setup: proto files, generated gRPC stubs, an Avro schema cache, and manual
  OAuth token refresh on the gRPC channel. CometD would have been perhaps a day's less
  work.
- Fewer worked examples in the wild. Salesforce's official Java example is the main
  reference.
- gRPC keepalive tuning matters through corporate proxies; a too-aggressive keepalive
  gets connections dropped. Pinned explicitly rather than left to defaults.

## Notes

Replay ids expire after 72 hours. If the service is down longer than that the stream
cannot be resumed, and recovery is a Bulk API reconciliation over orders modified since
the last checkpoint timestamp. That reconciliation job is required, not optional — a
long weekend outage is entirely plausible.
