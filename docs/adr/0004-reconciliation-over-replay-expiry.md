# ADR 0004 — Reconciliation is required, not optional

- **Status:** Accepted
- **Date:** 2026-01-15

## Context

Salesforce retains platform events for 72 hours. A replay id older than that is no
longer honoured: the subscription cannot be resumed from it, and the events in between
are simply not available any more.

The checkpoint design ([ADR 0001](0001-transactional-outbox.md)) guarantees we resume
exactly where we left off. It does not guarantee Salesforce still has anything there.

Three days sounds generous until you count how it is actually spent: a Friday evening
deployment that fails silently, nobody looking until Monday morning. That is 60 hours
before anyone starts investigating. A long weekend takes it past the window outright.

## Decision

A nightly job re-derives order state with SOQL over a 96 hour lookback and feeds the
results through the same `OrderChangeProcessor` the stream uses.

## Why it is safe to run alongside the stream

The job and the stream overlap by design — 96 hours of lookback against 72 hours of
retention, deliberately wider so nothing can fall between them. The overlap is harmless
because reconciled changes carry a **deterministic** event id:

```
recon:{orderId}:{lastModifiedDate}
```

Run the job twice with no intervening change and it produces the same id both times, so
the second run deduplicates away. An order the stream already delivered has a different
id and *will* be republished — which is why the ERP client upserts by order number
rather than inserting. At-least-once all the way down, absorbed at each hop.

## Consequences

**Good**

- The 72 hour window stops being a cliff. The worst case for a long outage is that
  orders arrive late, not that they never arrive.
- The same code path handles both sources, so reconciled orders get identical filtering,
  translation and outbox semantics. No second implementation to drift.

**Bad**

- SOQL cannot see deletions or hard-reverted states — it reports what an order looks
  like *now*, not the sequence it moved through. An order activated and cancelled inside
  the outage reconciles as cancelled, and the ERP never learns it was briefly open. For
  order fulfillment that is the right answer; it would not be for an audit trail.
- The lookback query is unindexed on `LastModifiedDate` in most orgs. Fine at Developer
  Edition volumes, worth checking before this meets a real production data set.
- It costs API calls against the daily allocation on every run, whether or not anything
  needs recovering.

## Alternatives considered

- **Alerting on checkpoint age and recovering by hand.** Cheaper, and the alert is worth
  having regardless — `JdbcReplayCheckpointStore.ageSeconds()` exists for it. But it
  makes recovery depend on someone being awake, which is precisely the assumption that
  fails on a long weekend.
- **Widening the lookback to a week.** More overlap, more API calls, no additional
  safety: past 72 hours the stream is already useless, so the only thing that matters is
  that the lookback exceeds retention. 96 hours does that with a day to spare.
