<#
.SYNOPSIS
    Runs Postgres and Kafka natively on Windows, with no Docker.

.DESCRIPTION
    Downloads the Postgres "binaries only" zip and the Kafka tarball into a gitignored
    .local/ directory, initialises both, and runs them as ordinary user processes.

    No administrator rights and no installer are involved: nothing is written outside
    the repo, no services are registered, and removing .local/ undoes everything. It is
    also considerably lighter on memory than Docker Desktop, which matters on a machine
    that is already tight.

.PARAMETER Action
    install  Download and initialise. Safe to re-run; skips what already exists.
    start    Start Postgres and Kafka, create the database and topics.
    stop     Stop both.
    status   Report what is running.
    reset    Stop and delete all data, keeping the downloads.

.EXAMPLE
    .\ops\local\local-infra.ps1 install
    .\ops\local\local-infra.ps1 start
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'start', 'stop', 'status', 'reset')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'

$RepoRoot  = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LocalDir  = Join-Path $RepoRoot '.local'
$PgDir     = Join-Path $LocalDir 'pgsql'
$PgData    = Join-Path $LocalDir 'pgdata'
$KafkaDir  = Join-Path $LocalDir 'kafka'
$KafkaLogs = Join-Path $LocalDir 'kafka-logs'
$KafkaProps = Join-Path $LocalDir 'kafka-server.properties'

$PgUrl    = 'https://get.enterprisedb.com/postgresql/postgresql-16.4-1-windows-x64-binaries.zip'
$KafkaUrl = 'https://archive.apache.org/dist/kafka/3.8.1/kafka_2.13-3.8.1.tgz'
$KafkaVersionDir = 'kafka_2.13-3.8.1'

$DbUser = 'ordersync'
$DbPass = 'ordersync'
$DbName = 'ordersync'

# 5433, not 5432. This machine may already have a PostgreSQL install (a PostGIS one is
# common on developer machines) holding the default port with credentials of its own.
# Claiming 5432 would either fail to start or, worse, silently connect us to somebody
# else's database. Our instance stays out of its way.
$PgPort = 5433

function Get-JavaExe {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    return 'java'
}

<#
    Kafka's shipped Windows .bat scripts enumerate every jar in libs/ into CLASSPATH,
    which overflows cmd.exe's 8191 character command line and fails with "The input
    line is too long". Invoking java ourselves with a single "libs\*" wildcard entry
    sidesteps it entirely, and avoids relocating Kafka to a short path.
#>
function Invoke-KafkaTool {
    param(
        [string]$MainClass,
        [string[]]$ToolArgs,
        [string[]]$JvmArgs = @()
    )
    $classpath = Join-Path $KafkaDir 'libs\*'
    $log4j = Join-Path $KafkaDir 'config\log4j.properties'
    $allArgs = @()
    $allArgs += $JvmArgs
    $allArgs += "-Dlog4j.configuration=file:`"$log4j`""
    $allArgs += '-cp'
    $allArgs += $classpath
    $allArgs += $MainClass
    $allArgs += $ToolArgs
    & (Get-JavaExe) @allArgs
}

function Write-Step($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Write-Ok($message)   { Write-Host "    $message" -ForegroundColor Green }
function Write-Warn($message) { Write-Host "    $message" -ForegroundColor Yellow }

function Test-PostgresRunning {
    $pgIsReady = Join-Path $PgDir 'bin\pg_isready.exe'
    if (-not (Test-Path $pgIsReady)) { return $false }
    & $pgIsReady -h localhost -p $PgPort -q 2>$null
    return ($LASTEXITCODE -eq 0)
}

function Test-KafkaRunning {
    $connection = Test-NetConnection -ComputerName localhost -Port 9092 -InformationLevel Quiet -WarningAction SilentlyContinue
    return $connection
}

# ---------------------------------------------------------------- install

function Invoke-Install {
    New-Item -ItemType Directory -Force $LocalDir | Out-Null

    if (Test-Path (Join-Path $PgDir 'bin\pg_ctl.exe')) {
        Write-Ok 'Postgres binaries already present.'
    } else {
        Write-Step 'Downloading Postgres 16 binaries (about 200 MB)'
        $zip = Join-Path $LocalDir 'pgsql.zip'
        # Progress rendering makes Invoke-WebRequest dramatically slower on 5.1.
        $previous = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $PgUrl -OutFile $zip
        $ProgressPreference = $previous
        Write-Step 'Extracting Postgres'
        Expand-Archive -Path $zip -DestinationPath $LocalDir -Force
        Remove-Item $zip
        Write-Ok 'Postgres binaries installed.'
    }

    if (Test-Path (Join-Path $KafkaDir 'bin\windows\kafka-server-start.bat')) {
        Write-Ok 'Kafka already present.'
    } else {
        $tgz = Join-Path $LocalDir 'kafka.tgz'
        if (Test-Path $tgz) {
            Write-Ok 'Kafka archive already downloaded, reusing it.'
        } else {
            Write-Step 'Downloading Kafka 3.8.1 (about 120 MB)'
            $previous = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri $KafkaUrl -OutFile $tgz
            $ProgressPreference = $previous
        }

        Write-Step 'Extracting Kafka'
        # --strip-components drops the kafka_2.13-3.8.1 wrapper so this lands directly
        # in .local/kafka. Extracting then renaming fails intermittently on Windows:
        # tar's handles, or an antivirus scan of the freshly written tree, can still
        # hold the directory when the move runs.
        New-Item -ItemType Directory -Force $KafkaDir | Out-Null
        tar -xzf $tgz -C $KafkaDir --strip-components=1
        if (-not (Test-Path (Join-Path $KafkaDir 'bin\windows\kafka-server-start.bat'))) {
            throw "Kafka extraction did not produce the expected layout under $KafkaDir"
        }
        # Leftovers from an earlier failed run.
        $stale = Join-Path $LocalDir $KafkaVersionDir
        if (Test-Path $stale) { Remove-Item -Recurse -Force $stale -ErrorAction SilentlyContinue }
        Remove-Item $tgz -ErrorAction SilentlyContinue
        Write-Ok 'Kafka installed.'
    }

    if (Test-Path (Join-Path $PgData 'PG_VERSION')) {
        Write-Ok 'Postgres data directory already initialised.'
    } else {
        Write-Step 'Initialising the Postgres data directory'
        $pwFile = Join-Path $LocalDir 'pgpw.txt'
        # -NoNewline matters: initdb treats a trailing newline as part of the password.
        Set-Content -Path $pwFile -Value $DbPass -NoNewline -Encoding ascii
        & (Join-Path $PgDir 'bin\initdb.exe') -D $PgData -U $DbUser --pwfile=$pwFile -E UTF8 --locale=C | Out-Null
        Remove-Item $pwFile
        Write-Ok "Postgres initialised (superuser '$DbUser')."
    }

    Write-Step 'Writing the Kafka broker config'
    # Our own config rather than the shipped one: KRaft single-node, and log.dirs
    # pointed somewhere short. Kafka on Windows trips over long paths in its log
    # directory, and the default lands under a deeply nested temp path.
    $logDirEscaped = $KafkaLogs -replace '\\', '/'
    @"
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093

listeners=PLAINTEXT://:9092,CONTROLLER://:9093
advertised.listeners=PLAINTEXT://localhost:9092
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT

log.dirs=$logDirEscaped
num.partitions=3
auto.create.topics.enable=true

# Single broker: nothing to replicate to.
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
"@ | Set-Content -Path $KafkaProps -Encoding ascii

    if (Test-Path (Join-Path $KafkaLogs 'meta.properties')) {
        Write-Ok 'Kafka storage already formatted.'
    } else {
        Write-Step 'Formatting Kafka storage'
        $clusterId = (Invoke-KafkaTool -MainClass 'kafka.tools.StorageTool' -ToolArgs @('random-uuid') | Select-Object -Last 1).Trim()
        if (-not $clusterId) { throw 'Could not generate a Kafka cluster id.' }
        Invoke-KafkaTool -MainClass 'kafka.tools.StorageTool' -ToolArgs @('format', '-t', $clusterId, '-c', $KafkaProps) | Out-Null
        Write-Ok "Kafka storage formatted (cluster $clusterId)."
    }

    Write-Host ''
    Write-Ok 'Install complete. Next:  .\ops\local\local-infra.ps1 start'
}

# ---------------------------------------------------------------- start

function Invoke-Start {
    if (-not (Test-Path (Join-Path $PgDir 'bin\pg_ctl.exe'))) {
        throw 'Not installed yet. Run:  .\ops\local\local-infra.ps1 install'
    }

    if (Test-PostgresRunning) {
        Write-Ok "Postgres already running on $PgPort."
    } else {
        Write-Step "Starting Postgres on $PgPort"
        & (Join-Path $PgDir 'bin\pg_ctl.exe') -D $PgData -l (Join-Path $LocalDir 'postgres.log') -o "-p $PgPort" -w start
        Write-Ok 'Postgres started.'
    }

    Write-Step "Ensuring database '$DbName' exists"
    $env:PGPASSWORD = $DbPass
    $existing = & (Join-Path $PgDir 'bin\psql.exe') -h localhost -p $PgPort -U $DbUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$DbName'" 2>$null
    if ($existing -match '1') {
        Write-Ok "Database '$DbName' already exists."
    } else {
        & (Join-Path $PgDir 'bin\createdb.exe') -h localhost -p $PgPort -U $DbUser $DbName
        Write-Ok "Database '$DbName' created."
    }

    if (Test-KafkaRunning) {
        Write-Ok 'Kafka already running.'
    } else {
        Write-Step 'Starting Kafka on 9092'
        $classpath = Join-Path $KafkaDir 'libs\*'
        $log4j = Join-Path $KafkaDir 'config\log4j.properties'
        # Start-Process joins ArgumentList with spaces and does no quoting of its own,
        # so every argument holding a path must be quoted here. This repo lives under
        # "Sales Force" — an unquoted argument is silently split at the space and java
        # reads the tail as a class name.
        Start-Process -FilePath (Get-JavaExe) `
            -ArgumentList @(
                '-Xmx768m', '-Xms256m',
                "`"-Dlog4j.configuration=file:$log4j`"",
                '-cp', "`"$classpath`"",
                'kafka.Kafka', "`"$KafkaProps`""
            ) `
            -RedirectStandardOutput (Join-Path $LocalDir 'kafka.log') `
            -RedirectStandardError (Join-Path $LocalDir 'kafka.err.log') `
            -WindowStyle Hidden
        Write-Host '    waiting for the broker' -NoNewline
        $ready = $false
        foreach ($attempt in 1..40) {
            Start-Sleep -Seconds 2
            Write-Host '.' -NoNewline
            if (Test-KafkaRunning) { $ready = $true; break }
        }
        Write-Host ''
        if (-not $ready) {
            throw "Kafka did not come up. See $LocalDir\kafka.log"
        }
        Write-Ok 'Kafka started.'
    }

    Write-Step 'Creating topics'
    foreach ($topic in @('orders.v1', 'orders.v1.DLQ', 'fulfillment.v1', 'fulfillment.v1.DLQ')) {
        # Relocated from kafka.admin in Kafka 3.x.
        Invoke-KafkaTool -MainClass 'org.apache.kafka.tools.TopicCommand' -ToolArgs @(
            '--bootstrap-server', 'localhost:9092',
            '--create', '--if-not-exists',
            '--topic', $topic,
            '--partitions', '3',
            '--replication-factor', '1'
        ) 2>$null | Out-Null
        Write-Ok $topic
    }

    Write-Host ''
    Write-Ok "Ready. Postgres on $PgPort, Kafka on 9092."
    Write-Host ''
    Write-Host '    Postgres is NOT on the default 5432, so the service needs to be told:' -ForegroundColor Yellow
    Write-Host "      DB_URL=jdbc:postgresql://localhost:$PgPort/$DbName" -ForegroundColor Yellow
    Write-Host '    Add that line to the .env at the repo root.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '    Prometheus, Grafana and Jaeger are not started; they are optional.' -ForegroundColor DarkGray
    Write-Host '    Metrics remain available at http://localhost:8080/actuator/prometheus' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- stop / status / reset

function Invoke-Stop {
    if (Test-KafkaRunning) {
        Write-Step 'Stopping Kafka'
        $stopBat = Join-Path $KafkaDir 'bin\windows\kafka-server-stop.bat'
        if (Test-Path $stopBat) { & $stopBat 2>$null | Out-Null }
        Start-Sleep -Seconds 3
        # The shipped stop script is unreliable on Windows; fall back to the port owner.
        if (Test-KafkaRunning) {
            $owner = Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue
            if ($owner) { Stop-Process -Id $owner.OwningProcess -Force -ErrorAction SilentlyContinue }
        }
        Write-Ok 'Kafka stopped.'
    } else {
        Write-Ok 'Kafka was not running.'
    }

    if (Test-PostgresRunning) {
        Write-Step 'Stopping Postgres'
        & (Join-Path $PgDir 'bin\pg_ctl.exe') -D $PgData -w stop
        Write-Ok 'Postgres stopped.'
    } else {
        Write-Ok 'Postgres was not running.'
    }
}

function Invoke-Status {
    if (Test-Path (Join-Path $PgDir 'bin\pg_ctl.exe')) {
        $pgInstalled = 'installed'
    } else {
        $pgInstalled = 'NOT installed'
    }
    if (Test-Path (Join-Path $KafkaDir 'bin\windows\kafka-server-start.bat')) {
        $kafkaInstalled = 'installed'
    } else {
        $kafkaInstalled = 'NOT installed'
    }
    if (Test-PostgresRunning) { $pgRunning = 'running' } else { $pgRunning = 'stopped' }
    if (Test-KafkaRunning) { $kafkaRunning = 'running' } else { $kafkaRunning = 'stopped' }

    Write-Host ''
    Write-Host ("  Postgres  {0,-14} {1}" -f $pgInstalled, $pgRunning)
    Write-Host ("  Kafka     {0,-14} {1}" -f $kafkaInstalled, $kafkaRunning)
    Write-Host ''
    Write-Host "  Data and downloads live in $LocalDir (gitignored)." -ForegroundColor DarkGray
}

function Invoke-Reset {
    Invoke-Stop
    Write-Step 'Deleting data directories'
    foreach ($path in @($PgData, $KafkaLogs)) {
        if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    }
    Write-Ok 'Data cleared. Run install again to re-initialise.'
}

switch ($Action) {
    'install' { Invoke-Install }
    'start'   { Invoke-Start }
    'stop'    { Invoke-Stop }
    'status'  { Invoke-Status }
    'reset'   { Invoke-Reset }
}
