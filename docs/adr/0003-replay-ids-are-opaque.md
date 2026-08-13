# ADR 0003 — Replay ids are opaque, and the checkpoint reflects that

- **Status:** Accepted
- **Date:** 2026-01-15

## Context

The first cut of this service modelled the Pub/Sub replay id as a `Long`. It read
nicely — `at replay id 100` in the acceptance criteria — and the checkpoint upsert
guarded against going backwards:

```sql
ON CONFLICT (stream_name) DO UPDATE
   SET replay_id = EXCLUDED.replay_id
 WHERE replay_checkpoint.replay_id < EXCLUDED.replay_id
```

That guard is wrong, and so is the type. `pubsub_api.proto` declares:

```protobuf
message ConsumerEvent {
  ProducerEvent event = 1;
  bytes replay_id = 2;
}
```

`bytes`, not `int64`. Salesforce documents replay ids as opaque and explicitly does not
guarantee they are ordered, comparable, or of stable width. The `Long` model would have
compiled, passed every test, and then failed on the first real event.

## Decision

Model the position as a `ReplayId` value class wrapping the raw bytes, base64-encoded
for storage. Make the checkpoint last-write-wins, and make the field nullable.

## Consequences

**The monotonic guard has to go.** There is no "greater" position to prefer. Safety
comes from upstream instead: one subscriber per stream, events applied in the order the
stream delivered them, each inside the transaction that recorded its work. The most
recently committed position is therefore the furthest along by construction.

The corollary is that **two subscribers on one stream name would corrupt the
checkpoint**. That is now an invariant the deployment has to hold, not something the
database enforces. It is enforced by there being a single `PubSubSubscriber` bean and
no partitioning of the subscription.

**Nullability turned out to matter.** Reconciliation ([ADR 0004](0004-reconciliation-over-replay-expiry.md))
re-derives orders from SOQL. Those orders never came from the stream, so they have no
position. Giving them a sentinel value would have written a position the subscription
had never been at, and the next restart would have resumed from nonsense. `ReplayId?`
with the processor skipping the write when it is null says exactly what is true.

**Tests read slightly worse.** Scenarios now say `at stream position "p100"` and the
step definition encodes that label to bytes. Marginally more ceremony than a number, in
exchange for the type no longer lying about what it holds.

## What this cost

The refactor touched the domain model, both checkpoint implementations, the migration,
the feature file and its step definitions — perhaps an hour, and only because it was
caught while reading the proto rather than after wiring up a live org. Had it been
found later it would have presented as "the service replays the same events forever
after a restart", which is a considerably worse afternoon.
