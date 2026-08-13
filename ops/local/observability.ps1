<#
.SYNOPSIS
    Runs Prometheus and Grafana natively on Windows, with no Docker.

.DESCRIPTION
    The compose stack provides these, but this is the fallback for a machine where the
    Docker daemon will not start. Both ship as portable zips: no installer, no admin
    rights, nothing written outside the repo's gitignored .local/ directory.

    The provisioning files under ops/grafana are written for compose, where services
    address each other by container name. This script generates a parallel set that
    points at localhost instead, so the same dashboard works either way.

.PARAMETER Action
    install  Download and unpack. Safe to re-run.
    start    Start both, wired to the running order-sync.
    stop     Stop both.
    status   Report what is listening.

.EXAMPLE
    .\ops\local\observability.ps1 install
    .\ops\local\observability.ps1 start
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'start', 'stop', 'status')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'

$RepoRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LocalDir   = Join-Path $RepoRoot '.local'
$PromDir    = Join-Path $LocalDir 'prometheus'
$GrafanaDir = Join-Path $LocalDir 'grafana'
$PromConfig = Join-Path $LocalDir 'prometheus-local.yml'
$PromData   = Join-Path $LocalDir 'prometheus-data'
$GfProvision = Join-Path $LocalDir 'grafana-provisioning'
$GfData     = Join-Path $LocalDir 'grafana-data'

# Not Grafana's default 3000. The 3000-3002 range is where every Node dev server
# lands, and on a working machine those are usually all taken. Override with
# -GrafanaPort if 3010 is busy too.
$GrafanaPort = 3010

$PromUrl     = 'https://github.com/prometheus/prometheus/releases/download/v2.55.1/prometheus-2.55.1.windows-amd64.zip'
$PromVersion = 'prometheus-2.55.1.windows-amd64'
$GrafanaUrl     = 'https://dl.grafana.com/oss/release/grafana-11.3.1.windows-amd64.zip'
$GrafanaVersion = 'grafana-v11.3.1'

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    $m" -ForegroundColor Green }

function Test-Listening($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

<#
    "Something is listening" is not the same as "Grafana is listening". Ask the health
    endpoint and check the answer looks like Grafana, so a dev server squatting on the
    port is reported as a conflict rather than mistaken for success.
#>
function Test-GrafanaUp($port) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$port/api/health" -TimeoutSec 3 -UseBasicParsing
        return ($r.Content -match '"database"')
    } catch {
        return $false
    }
}

function Expand-Release($url, $expectedDir, $targetDir, $name) {
    if (Test-Path $targetDir) {
        Write-Ok "$name already present."
        return
    }
    Write-Step "Downloading $name"
    $zip = Join-Path $LocalDir "$name.zip"
    if (-not (Test-Path $zip)) {
        $previous = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $zip
        $ProgressPreference = $previous
    }
    Write-Step "Extracting $name"
    Expand-Archive -Path $zip -DestinationPath $LocalDir -Force
    $extracted = Join-Path $LocalDir $expectedDir
    if (-not (Test-Path $extracted)) {
        throw "Expected $extracted after extracting $name. Check the archive layout."
    }
    Move-Item $extracted $targetDir
    Remove-Item $zip -ErrorAction SilentlyContinue
    Write-Ok "$name installed."
}

function Invoke-Install {
    New-Item -ItemType Directory -Force $LocalDir | Out-Null
    Expand-Release $PromUrl    $PromVersion    $PromDir    'prometheus'
    Expand-Release $GrafanaUrl $GrafanaVersion $GrafanaDir 'grafana'

    Write-Step 'Writing the localhost scrape config'
    # ops/prometheus/prometheus.yml targets host.docker.internal, which only resolves
    # from inside a container. Running natively, the service is simply on localhost.
    @"
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: order-sync
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["localhost:8080"]

  - job_name: prometheus
    static_configs:
      - targets: ["localhost:9090"]
"@ | Set-Content -Path $PromConfig -Encoding ascii

    Write-Step 'Writing Grafana provisioning'
    New-Item -ItemType Directory -Force (Join-Path $GfProvision 'datasources') | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $GfProvision 'dashboards') | Out-Null

    @"
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://localhost:9090
    isDefault: true
"@ | Set-Content -Path (Join-Path $GfProvision 'datasources\prometheus.yml') -Encoding ascii

    $dashboardDir = (Join-Path $GfProvision 'dashboards') -replace '\\', '/'
    @"
apiVersion: 1
providers:
  - name: order-sync
    orgId: 1
    type: file
    allowUiUpdates: true
    options:
      path: $dashboardDir
"@ | Set-Content -Path (Join-Path $GfProvision 'dashboards\provider.yml') -Encoding ascii

    # The dashboard itself is identical either way; only the datasource URL differs.
    Copy-Item (Join-Path $RepoRoot 'ops\grafana\provisioning\dashboards\order-sync.json') `
              (Join-Path $GfProvision 'dashboards\order-sync.json') -Force

    Write-Host ''
    Write-Ok 'Install complete. Next:  .\ops\local\observability.ps1 start'
}

function Invoke-Start {
    if (-not (Test-Path $PromDir)) {
        throw 'Not installed. Run:  .\ops\local\observability.ps1 install'
    }
    if (-not (Test-Listening 8080)) {
        Write-Host '    order-sync is not running, so there will be nothing to graph.' -ForegroundColor Yellow
        Write-Host '    Start it with:  .\ops\local\run-services.ps1 start' -ForegroundColor Yellow
    }

    New-Item -ItemType Directory -Force $PromData | Out-Null
    New-Item -ItemType Directory -Force $GfData | Out-Null

    if (Test-Listening 9090) {
        Write-Ok 'Prometheus already running.'
    } else {
        Write-Step 'Starting Prometheus on 9090'
        Start-Process -FilePath (Join-Path $PromDir 'prometheus.exe') `
            -ArgumentList @(
                "--config.file=`"$PromConfig`"",
                "--storage.tsdb.path=`"$PromData`"",
                '--web.listen-address=127.0.0.1:9090'
            ) `
            -WorkingDirectory $PromDir `
            -RedirectStandardOutput (Join-Path $LocalDir 'prometheus.log') `
            -RedirectStandardError (Join-Path $LocalDir 'prometheus.err.log') `
            -WindowStyle Hidden
        Start-Sleep -Seconds 3
        Write-Ok 'Prometheus started.'
    }

    if (Test-GrafanaUp $GrafanaPort) {
        Write-Ok "Grafana already running on $GrafanaPort."
    } else {
        if (Test-Listening $GrafanaPort) {
            throw "Port $GrafanaPort is in use by something that is not Grafana. " +
                  "Free it, or edit `$GrafanaPort in this script."
        }
        Write-Step "Starting Grafana on $GrafanaPort"
        # Configured by environment rather than editing grafana.ini, so the unpacked
        # release stays pristine and re-installing does not lose settings.
        $env:GF_SERVER_HTTP_PORT = "$GrafanaPort"
        $env:GF_PATHS_PROVISIONING = $GfProvision
        $env:GF_PATHS_DATA = $GfData
        $env:GF_SECURITY_ADMIN_USER = 'admin'
        $env:GF_SECURITY_ADMIN_PASSWORD = 'admin'
        $env:GF_AUTH_ANONYMOUS_ENABLED = 'true'
        $env:GF_AUTH_ANONYMOUS_ORG_ROLE = 'Admin'
        $env:GF_ANALYTICS_REPORTING_ENABLED = 'false'
        $env:GF_ANALYTICS_CHECK_FOR_UPDATES = 'false'

        Start-Process -FilePath (Join-Path $GrafanaDir 'bin\grafana.exe') `
            -ArgumentList @('server', '--homepath', "`"$GrafanaDir`"") `
            -WorkingDirectory $GrafanaDir `
            -RedirectStandardOutput (Join-Path $LocalDir 'grafana.log') `
            -RedirectStandardError (Join-Path $LocalDir 'grafana.err.log') `
            -WindowStyle Hidden

        Write-Host '    waiting' -NoNewline
        $ready = $false
        foreach ($attempt in 1..40) {
            Start-Sleep -Seconds 2
            Write-Host '.' -NoNewline
            if (Test-GrafanaUp $GrafanaPort) { $ready = $true; break }
        }
        Write-Host ''
        if (-not $ready) { throw "Grafana did not start. See $LocalDir\grafana.log" }
        Write-Ok 'Grafana started.'
    }

    Write-Host ''
    Write-Ok "Grafana     http://localhost:$GrafanaPort/d/order-sync   (anonymous, no login)"
    Write-Ok 'Prometheus  http://localhost:9090'
}

function Invoke-Stop {
    foreach ($svc in @(@{p = $GrafanaPort; n = "Grafana" }, @{p = 9090; n = 'Prometheus' })) {
        $c = Get-NetTCPConnection -LocalPort $svc.p -State Listen -ErrorAction SilentlyContinue
        if ($c) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
            Write-Ok "$($svc.n) stopped."
        } else {
            Write-Ok "$($svc.n) was not running."
        }
    }
}

function Invoke-Status {
    Write-Host ''
    foreach ($svc in @(@{p = $GrafanaPort; n = "grafana" }, @{p = 9090; n = 'prometheus' }, @{p = 8080; n = 'order-sync' })) {
        if (Test-Listening $svc.p) { $state = 'running' } else { $state = 'stopped' }
        Write-Host ("  {0,-12} {1,-6} {2}" -f $svc.n, $svc.p, $state)
    }
    Write-Host ''
}

switch ($Action) {
    'install' { Invoke-Install }
    'start'   { Invoke-Start }
    'stop'    { Invoke-Stop }
    'status'  { Invoke-Status }
}
