# sf-order-sync

Bidirectional integration between Salesforce and an ERP, over Kafka.

Salesforce owns Orders. The ERP owns fulfillment. Neither calls the other: a Kotlin /
Spring Boot service brokers both directions, dealing with the parts that make this
genuinely hard — at-least-once delivery, replay after downtime, poison messages, and
Salesforce's API governor limits.

Full design in [docs/architecture.md](docs/architecture.md). Org setup in
[docs/salesforce-setup.md](docs/salesforce-setup.md).

## Layout

```
services/order-sync/     Kotlin + Spring Boot — the integration service
services/mock-erp/       Stand-in for Oracle EBS, so compose has a real HTTP peer
salesforce/              SFDX package: Order_Change__e, Apex, LWC status panel
ops/                     Prometheus, Grafana dashboard, Dockerfiles
docs/                    Architecture, ADRs, org setup
```

## Prerequisites

- JDK 17
- Docker — for compose and for the Testcontainers-backed tests
- A Salesforce Developer Edition org, for the live Pub/Sub path
- The Salesforce CLI, to deploy `salesforce/`:

  ```bash
  npm install --global @salesforce/cli
  ```

  Note that `sf` commands must be run from inside `salesforce/` — that is where
  `sfdx-project.json` lives, and the CLI resolves the project from the working
  directory.

## Tests

Two lanes, deliberately:

```bash
./gradlew test              # acceptance + unit + WireMock. No Docker. Seconds.
./gradlew integrationTest   # Testcontainers: real Postgres, real Kafka. Needs Docker.
./gradlew check             # both
```

The split exists so a developer without a Docker daemon still gets a real signal
instead of a hang. CI runs `check`.

## Running locally

```bash
docker compose up -d
./gradlew :services:mock-erp:bootRun &
./gradlew :services:order-sync:bootRun
```

### Without Docker

Docker is convenience, not architecture. If the daemon will not start, run Postgres and
Kafka natively instead:

```powershell
.\ops\local\local-infra.ps1 install   # one time, ~3 min
.\ops\local\local-infra.ps1 start     # Postgres on 5432, Kafka on 9092, topics created
.\ops\local\local-infra.ps1 status
.\ops\local\local-infra.ps1 stop
```

Everything lands in a gitignored `.local/`. No administrator rights, no installer, no
Windows services — deleting `.local/` undoes it completely. It also uses noticeably less
memory than Docker Desktop.

What you give up: Kafka UI, Prometheus, Grafana and Jaeger are not started. Metrics are
still exposed at `/actuator/prometheus`, and `.local/kafka/bin/windows/kafka-console-consumer.bat`
reads topics from the command line.

The one thing that genuinely needs Docker is `./gradlew integrationTest`, since
Testcontainers has no substitute. CI runs that job on every push, so it stays covered.

| Service | URL |
| --- | --- |
| order-sync | http://localhost:8080/actuator/health |
| mock-erp | http://localhost:8081/api/orders |
| Kafka UI | http://localhost:8085 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (anonymous, dashboard pre-provisioned) |
| Jaeger | http://localhost:16686 |

The service starts without Salesforce credentials — `ordersync.salesforce.enabled`
defaults to false, so the Kafka and ERP halves run standalone. Set `SF_ENABLED=true`
once the org is configured.

### Operator endpoints

```bash
curl localhost:8080/admin/outbox                          # depth and oldest-row age
curl -XPOST localhost:8080/admin/dlq/orders/replay?max=50 # drain the DLQ back
```

## Deploying the Salesforce package

```bash
cd salesforce
sf org login web --alias ordersync-dev
sf project deploy start --target-org ordersync-dev
sf apex run test --target-org ordersync-dev --code-coverage --result-format human
```

Then drop the **Integration Status** LWC onto the Order record page in the Lightning
App Builder.

## How this repo is built

Acceptance criteria come first. Each slice began by writing the Gherkin scenarios and a
stub that throws, confirming the suite failed for the right reason, and only then
implementing until it passed.

**The git history does not show that**, and it is worth being straight about why: the
project was built in a single working session and committed at the end, so the initial
import is thematic rather than a red-then-green sequence. If you want the history as
evidence rather than the process, the commits from here forward are the honest place to
look for it.

What the code does still show is the boundary that made test-first practical: business
rules live in `com.ordersync.domain`, which has no Spring, Kafka or JDBC imports. That is
what lets the acceptance suite run against in-memory adapters in milliseconds while the
adapters get their own contract tests against real infrastructure.

## What is built

| Slice | |
| --- | --- |
| 1 | `OrderChangeProcessor` — dedupe, filter, translate, checkpoint |
| 2 | Postgres adapters, Flyway migrations, Testcontainers contract tests |
| 3 | Outbox relay to `orders.v1`, depth and age gauges |
| 4 | ERP consumer, retry classification, DLQ, replay endpoint |
| 5 | Pub/Sub API gRPC client, Avro decoding, replay resume |
| 6 | Reverse flow: fulfillment webhook, batch consumer, Composite API upsert |
| 7 | OpenTelemetry tracing, Grafana dashboard |
| 8 | Reconciliation job for expired replay windows |
| 9 | LWC integration status panel |

### Not done

- **The live Pub/Sub path has never been run against a real org.** The gRPC client,
  Avro decoding and replay resume are written and compile against Salesforce's
  published proto, but every test of that layer is offline. Treat slice 5 as unverified
  until it has streamed one real event.
- No authentication on the `/admin` endpoints. Fine for a demo stack, not for anything else.
- The Apex and LWC have not been deployed to an org, so their tests are unrun.
