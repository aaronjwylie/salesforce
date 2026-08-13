<#
.SYNOPSIS
    Drives realistic traffic through the running service, so the dashboard has
    something to show.

.DESCRIPTION
    Writes order events straight into the outbox table rather than inventing metrics.
    That entry point is deliberate: one insert exercises the entire downstream chain --
    the relay claims it with SKIP LOCKED, publishes to Kafka, the consumer picks it up
    and pushes it to the ERP -- so every counter, gauge and timer on the dashboard moves
    for the same reason it would in production.

    A slice of the traffic is deliberately bad, because a dashboard where nothing ever
    fails tells you nothing about whether the failure panels work.

.PARAMETER Orders
    How many good orders to push through. Default 60.

.PARAMETER Failures
    How many undeliverable fulfillment updates to send, to exercise the DLQ. Default 8.

.PARAMETER SpreadSeconds
    Roughly how long to spread the traffic over. Default 180. Prometheus scrapes every
    5s, so a few minutes gives the graphs shape rather than a single spike.

.EXAMPLE
    .\ops\local\generate-load.ps1
    .\ops\local\generate-load.ps1 -Orders 200 -SpreadSeconds 300
#>
param(
    [int]$Orders = 60,
    [int]$Failures = 8,
    [int]$SpreadSeconds = 180
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Psql     = Join-Path $RepoRoot '.local\pgsql\bin\psql.exe'
$PgPort   = 5433
$env:PGPASSWORD = 'ordersync'

if (-not (Test-Path $Psql)) { throw 'Postgres is not installed locally. Run: .\ops\local\local-infra.ps1 install' }

function Test-Listening($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}
if (-not (Test-Listening 8080)) { throw 'order-sync is not running. Run: .\ops\local\run-services.ps1 start' }
if (-not (Test-Listening 8081)) { throw 'mock-erp is not running. Run: .\ops\local\run-services.ps1 start' }

$SqlFile  = Join-Path $env:TEMP 'ordersync-load.sql'
$statuses = @('ACTIVATED', 'ACTIVATED', 'ACTIVATED', 'FULFILLED', 'CANCELLED')
$delayMs  = [int](($SpreadSeconds * 1000) / [Math]::Max($Orders, 1))

Write-Host "==> Pushing $Orders orders over about $SpreadSeconds seconds" -ForegroundColor Cyan
Write-Host "    one insert per $delayMs ms, plus $Failures deliberate failures" -ForegroundColor DarkGray

$failureAt = @()
if ($Failures -gt 0) {
    $step = [Math]::Max([int]($Orders / $Failures), 1)
    for ($i = $step; $i -lt $Orders; $i += $step) { $failureAt += $i }
}

for ($i = 1; $i -le $Orders; $i++) {
    $orderNumber = 'LOAD-{0:D5}' -f $i
    $eventId     = [guid]::NewGuid().ToString()
    $status      = $statuses[(Get-Random -Maximum $statuses.Count)]
    $amount      = [Math]::Round((Get-Random -Minimum 50 -Maximum 9000) + 0.99, 2)
    $occurredAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')

    # Shape matches com.ordersync.domain.OrderEvent. salesforceOrderId is synthetic:
    # the write-back to Salesforce will fail for these and be logged, which is correct
    # behaviour -- it must not fail the message, because the ERP already has the order.
    $payload = @{
        eventId           = $eventId
        orderNumber       = $orderNumber
        salesforceOrderId = '801LOAD00000000AAA'
        accountExternalId = 'ACCT-42'
        status            = $status
        totalAmount       = $amount
        currencyCode      = 'CAD'
        occurredAt        = $occurredAt
    } | ConvertTo-Json -Compress

    $escaped = $payload -replace "'", "''"
    $sql = @"
INSERT INTO processed_event (event_id, order_number) VALUES ('$eventId', '$orderNumber') ON CONFLICT DO NOTHING;
INSERT INTO outbox (topic, message_key, payload) VALUES ('orders.v1', '$orderNumber', '$escaped'::jsonb);
"@
    # Via a file, not -c. PowerShell mangles double quotes when passing arguments to a
    # native executable, which silently strips them out of the JSON payload and leaves
    # Postgres complaining about an invalid token.
    Set-Content -Path $SqlFile -Value $sql -Encoding utf8
    & $Psql -h localhost -p $PgPort -U ordersync -d ordersync -q -f $SqlFile | Out-Null

    if ($failureAt -contains $i) {
        # No matching ERP_Order_Id__c in Salesforce, so this is rejected and dead-lettered.
        try {
            Invoke-WebRequest -Uri 'http://localhost:8080/webhooks/erp/fulfillment' `
                -Method Post -ContentType 'application/json' -TimeoutSec 5 -UseBasicParsing `
                -Body (@{ erpOrderId = "MISSING-$i"; orderNumber = $orderNumber; status = 'Shipped' } | ConvertTo-Json) `
                | Out-Null
        } catch { }
    }

    if ($i % 10 -eq 0) { Write-Host "    $i / $Orders" -ForegroundColor DarkGray }
    Start-Sleep -Milliseconds $delayMs
}

Write-Host ''
Write-Host '==> Waiting for the queue to drain' -ForegroundColor Cyan
foreach ($attempt in 1..30) {
    $depth = (Invoke-WebRequest -Uri 'http://localhost:8080/admin/outbox' -UseBasicParsing -TimeoutSec 5).Content
    if ($depth -match '"depth":0') { break }
    Start-Sleep -Seconds 2
}

$erp = (Invoke-WebRequest -Uri 'http://localhost:8081/api/orders' -UseBasicParsing -TimeoutSec 10).Content
$count = ([regex]::Matches($erp, '"erpOrderId"')).Count
Write-Host "    ERP now holds $count orders." -ForegroundColor Green
Write-Host ''
Write-Host '    Dashboard: http://localhost:3010/d/order-sync' -ForegroundColor Green
Write-Host '    Set the time range to Last 15 minutes.' -ForegroundColor DarkGray
