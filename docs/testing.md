# How to test this

Three levels: the automated suites, a manual end-to-end run, and the failure drills.
The failure drills are the ones worth showing someone.

## 1. Automated

```bash
./gradlew test              # ~20s, no Docker. Acceptance, contract, decoding.
./gradlew integrationTest   # needs Docker. Real Postgres and Kafka.
./gradlew check             # both
```

```bash
cd salesforce
sf apex run test --target-org ordersync-dev --code-coverage --result-format human
```

If Docker will not start, `integrationTest` runs in CI on every push. That is not a
downgrade — a clean runner is a better signal than a developer laptop.

## 2. Manual, end to end

### Start the infrastructure

```powershell
.\ops\local\local-infra.ps1 start
```

Or `docker compose up -d` if Docker works. Note the port difference: the script uses
**5433**, compose uses 5432. `.env` must match:

```
DB_URL=jdbc:postgresql://localhost:5433/ordersync
```

### Check the org side first

Do this before starting anything — it fails fast and tells you why:

```bash
set -a; . ./.env; set +a
curl -s -X POST "$SF_LOGIN_URL/services/oauth2/token" \
  -d grant_type=client_credentials \
  -d client_id="$SF_CLIENT_ID" -d client_secret="$SF_CLIENT_SECRET"
```

Wanted: a body containing `access_token`. See
[salesforce-setup.md](salesforce-setup.md) for what each failure actually means.

### Start the services

```powershell
.\ops\local\run-services.ps1 start    # builds the jars if missing, starts both
.\ops\local\run-services.ps1 logs     # tail order-sync
.\ops\local\run-services.ps1 stop
```

This runs the jars with `java` directly. `gradlew bootRun` also works, but it holds a
Gradle daemon open for the life of the service, which is around a gigabyte.

> **Build and run are not simultaneous on a memory-constrained machine.** Kafka,
> Postgres and two Spring services leave little room, and the Gradle daemon will fail
> to start with *"The paging file is too small for this operation to complete"*. Stop
> the services, build, then start them again. The same applies to the `sf` CLI, which
> is a Node process that will crash rather than degrade.

Healthy startup looks like:

```
Successfully applied 1 migration to schema "public", now at version v1
Subscribing to /event/Order_Change__e from the tip of the stream
Started OrderSyncApplicationKt in 7.2 seconds
```

### The happy path

In Salesforce, open Order **00000100** and click **Activate**. Then watch, in order:

| Where | Expect |
| --- | --- |
| order-sync log | `Published order 00000100 in status ACTIVATED` |
| `curl localhost:8080/admin/outbox` | depth returns to 0 within a second |
| `curl localhost:8081/api/orders` | the order, with an `erpOrderId` |
| order-sync log | `Order 00000100 is ERP-1 in the ERP` |

Then the reverse direction:

```bash
curl -X POST localhost:8080/webhooks/erp/fulfillment \
  -H "Content-Type: application/json" \
  -d '{"erpOrderId":"ERP-1","orderNumber":"00000100","status":"Shipped"}'
```

The Order in Salesforce should show `Fulfillment_Status__c = Shipped` within a couple of
seconds, and the Integration Status panel on the record page should say so.

### Reading the topics directly

```powershell
$cp = "$PWD\.local\kafka\libs\*"
java -Xmx128m -cp "$cp" kafka.tools.ConsoleConsumer `
  --bootstrap-server localhost:9092 --topic orders.v1 --from-beginning --timeout-ms 8000
```

Keep the heap small. Several JVMs are already running and the default 512m start-up
allocation will fail on a loaded machine.

## 3. The failure drills

These are the point of the project. Each one takes under a minute.

### Nothing is lost when the service dies mid-flight

1. Stop `mock-erp` so orders cannot be delivered.
2. Activate an order in Salesforce.
3. `curl localhost:8080/admin/outbox` — depth is 1, and the age starts climbing.
4. **Kill order-sync with Ctrl-C.** Restart it.
5. Start `mock-erp` again.
6. The order arrives. Nothing was lost, and nothing was duplicated.

The transactional outbox is what makes this true; see
[ADR 0001](adr/0001-transactional-outbox.md).

### A duplicate does not become a second order

Activate an order, then edit an unrelated field and save. Only changes the ERP cares
about republish, and even a genuine redelivery is absorbed — `processed_event` has a
primary key on the event id, so the second attempt is a no-op.

Prove the store directly:

```sql
SELECT event_id, order_number, processed_at FROM processed_event ORDER BY processed_at DESC LIMIT 5;
```

### A poison message does not block the queue

```bash
curl -X POST localhost:8080/webhooks/erp/fulfillment \
  -H "Content-Type: application/json" \
  -d '{"erpOrderId":"NOPE","orderNumber":"NOPE","status":"Shipped"}'
```

The ERP rejects it. Watch the log: it goes to `fulfillment.v1.DLQ` **without four retry
attempts**, because a 4xx is classified as permanent. Good messages behind it keep
flowing. Then bring it back:

```bash
curl -X POST "localhost:8080/admin/dlq/fulfillment/replay?max=10"
# {"dlqTopic":"fulfillment.v1.DLQ","targetTopic":"fulfillment.v1","replayed":1,"failed":0}
```

### Restarting resumes the stream rather than replaying it

```sql
SELECT stream_name, replay_id, updated_at FROM replay_checkpoint;
```

Note the value, restart the service, and watch the log say
`Subscribing to /event/Order_Change__e from checkpoint <that value>` rather than *from
the tip of the stream*. Events raised while it was down arrive on reconnect.

### Recovery when the replay window has expired

Salesforce keeps events for 72 hours. Past that the checkpoint is useless and
reconciliation is the only way back:

```bash
curl -X POST localhost:8080/admin/reconcile
```

It re-derives order state from SOQL. Run it twice — the second run republishes nothing,
because reconciled changes carry a deterministic event id the dedupe ledger already
holds. See [ADR 0004](adr/0004-reconciliation-over-replay-expiry.md).

## What to check when something is wrong

| Symptom | Look at |
| --- | --- |
| Nothing arrives from Salesforce | Auth first — the curl above. Then whether `SF_ENABLED=true`. |
| Orders stop reaching the ERP | `/admin/outbox`. A climbing age means the relay is stuck. |
| Fields report "No such column" | The permission set is not assigned. |
| Service will not start | Postgres port. The script uses 5433, not the default. |
