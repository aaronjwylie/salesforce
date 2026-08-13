<#
.SYNOPSIS
    Starts, stops and inspects the two Spring services for a demo.

.DESCRIPTION
    Runs the built jars with java directly rather than `gradlew bootRun`. bootRun keeps
    a Gradle daemon alive for the lifetime of the service, which on a machine already
    running Kafka, Postgres and two JVMs is the difference between a working demo and a
    paging-file exhaustion crash.

    Heaps are capped deliberately and are far smaller than the JVM would choose. These
    services are I/O bound; they do not need the default quarter-of-RAM.

.PARAMETER Action
    start   Build the jars if missing, then start both services.
    stop    Stop both.
    status  Report what is listening.
    logs    Tail the order-sync log.

.EXAMPLE
    .\ops\local\run-services.ps1 start
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'logs')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LogDir   = Join-Path $RepoRoot '.local'
$OrderJar = Join-Path $RepoRoot 'services\order-sync\build\libs\order-sync-0.1.0-SNAPSHOT.jar'
$ErpJar   = Join-Path $RepoRoot 'services\mock-erp\build\libs\mock-erp-0.1.0-SNAPSHOT.jar'

function Test-Listening($port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return [bool]$c
}

function Stop-Port($port, $name) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($c) {
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        Write-Host "    $name stopped." -ForegroundColor Green
    } else {
        Write-Host "    $name was not running." -ForegroundColor Green
    }
}

function Start-Service-Jar($jar, $port, $name, $heap) {
    if (Test-Listening $port) {
        Write-Host "    $name already listening on $port." -ForegroundColor Green
        return
    }
    if (-not (Test-Path $jar)) { throw "Missing $jar. Run: .\gradlew :services:$name`:bootJar" }

    Write-Host "==> Starting $name on $port" -ForegroundColor Cyan
    # Working directory is the repo root so Spring's spring.config.import finds ./.env.
    Start-Process -FilePath 'java' `
        -ArgumentList @("-Xmx$heap", '-XX:+UseSerialGC', '-jar', "`"$jar`"") `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput (Join-Path $LogDir "$name.log") `
        -RedirectStandardError (Join-Path $LogDir "$name.err.log") `
        -WindowStyle Hidden

    Write-Host '    waiting' -NoNewline
    foreach ($attempt in 1..45) {
        Start-Sleep -Seconds 2
        Write-Host '.' -NoNewline
        if (Test-Listening $port) {
            Write-Host ''
            Write-Host "    $name up." -ForegroundColor Green
            return
        }
    }
    Write-Host ''
    throw "$name did not come up. See $LogDir\$name.log"
}

switch ($Action) {
    'start' {
        if (-not (Test-Listening 9092)) {
            throw 'Kafka is not running. Start it first:  .\ops\local\local-infra.ps1 start'
        }
        New-Item -ItemType Directory -Force $LogDir | Out-Null
        Start-Service-Jar $ErpJar   8081 'mock-erp'   '192m'
        Start-Service-Jar $OrderJar 8080 'order-sync' '448m'

        Write-Host ''
        Write-Host '    order-sync  http://localhost:8080/actuator/health' -ForegroundColor Green
        Write-Host '    mock-erp    http://localhost:8081/api/orders' -ForegroundColor Green
        Write-Host "    logs        $LogDir\order-sync.log" -ForegroundColor DarkGray
    }
    'stop' {
        Stop-Port 8080 'order-sync'
        Stop-Port 8081 'mock-erp'
    }
    'status' {
        Write-Host ''
        foreach ($svc in @(@{p = 8080; n = 'order-sync' }, @{p = 8081; n = 'mock-erp' },
                           @{p = 9092; n = 'kafka' }, @{p = 5433; n = 'postgres' })) {
            if (Test-Listening $svc.p) { $state = 'running' } else { $state = 'stopped' }
            Write-Host ("  {0,-12} {1,-6} {2}" -f $svc.n, $svc.p, $state)
        }
        Write-Host ''
    }
    'logs' {
        Get-Content (Join-Path $LogDir 'order-sync.log') -Tail 40 -Wait
    }
}
