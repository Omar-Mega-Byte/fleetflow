param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][string]$KeyFile,
    [Parameter(Mandatory)][string]$RemoteDestination,
    [Parameter(Mandatory)][string]$DiscordWebhookFile,
    [ValidateRange(1, 365)][int]$RetentionDays = 7,
    [switch]$EnsureProjectRunning
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
if ($Project -cnotmatch '^[a-z][a-z0-9-]{1,40}$') { throw 'Invalid Compose project name.' }
$secretPath = (Resolve-Path -LiteralPath $DiscordWebhookFile).Path
$webhook = [IO.File]::ReadAllText($secretPath).Trim()
$uri = $null
if (-not [Uri]::TryCreate($webhook, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or $uri.Host -notin @('discord.com', 'discordapp.com') -or
        -not $uri.AbsolutePath.StartsWith('/api/webhooks/')) {
    throw 'Discord webhook file is invalid.'
}

try {
    if ($EnsureProjectRunning) {
        $dockerDesktop = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'
        $engineReady = $false
        try {
            & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
            $engineReady = ($LASTEXITCODE -eq 0)
        } catch { $engineReady = $false }
        if (-not $engineReady -and -not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
            if (-not (Test-Path -LiteralPath $dockerDesktop -PathType Leaf)) {
                throw 'Docker Desktop is unavailable.'
            }
            Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
        }
        $engineDeadline = [DateTime]::UtcNow.AddMinutes(5)
        while (-not $engineReady -and [DateTime]::UtcNow -lt $engineDeadline) {
            Start-Sleep -Seconds 5
            try {
                & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
                $engineReady = ($LASTEXITCODE -eq 0)
            } catch { $engineReady = $false }
        }
        if (-not $engineReady) { throw 'Docker Desktop engine is unavailable.' }
        Write-Output 'Docker Desktop engine is ready.'

        $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
        $env:DISCORD_WEBHOOK_FILE = $secretPath
        $composeArgs = @('compose', '--env-file', $EnvironmentFile, '-p', $Project,
            '-f', (Join-Path $root 'compose.yaml'),
            '-f', (Join-Path $root 'compose.monitoring.yaml'),
            '-f', (Join-Path $root 'compose.alerting.yaml'), '--profile', 'monitoring')
        # Refresh containers when the Compose contract changes (for example,
        # when a healthcheck is added) while retaining the project's named volumes.
        & docker @composeArgs up -d --no-build | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Compose project could not be started.' }
        $ready = $false
        $projectDeadline = [DateTime]::UtcNow.AddMinutes(5)
        while (-not $ready -and [DateTime]::UtcNow -lt $projectDeadline) {
            $rows = @(& docker @composeArgs ps --format json | ConvertFrom-Json)
            if ($LASTEXITCODE -ne 0) { throw 'Compose project status failed.' }
            $postgres = $rows | Where-Object Service -eq 'postgres' | Select-Object -First 1
            $keycloak = $rows | Where-Object Service -eq 'keycloak' | Select-Object -First 1
            $app = $rows | Where-Object Service -eq 'app' | Select-Object -First 1
            $ready = ($null -ne $postgres -and $postgres.State -eq 'running' -and
                $postgres.Health -eq 'healthy' -and $null -ne $keycloak -and
                $keycloak.State -eq 'running' -and $null -ne $app -and
                $app.State -eq 'running' -and $app.Health -eq 'healthy')
            if (-not $ready) { Start-Sleep -Seconds 5 }
        }
        if (-not $ready) { throw 'Compose project did not become healthy.' }
        Write-Output 'Compose backup source is healthy.'
    }
    & (Join-Path $PSScriptRoot 'Backup-FleetFlow.ps1') -Project $Project `
        -EnvironmentFile $EnvironmentFile -Destination $Destination -KeyFile $KeyFile `
        -RemoteDestination $RemoteDestination -RetentionDays $RetentionDays
} catch {
    $failure = $_
    $content = "FleetFlow backup FAILED for $Project at $([DateTimeOffset]::UtcNow.ToString('o')). Inspect the local scheduled-task log."
    $body = @{ content = $content } | ConvertTo-Json -Compress
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(10)
    $payload = $null
    $response = $null
    try {
        $payload = [Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
        $response = $client.PostAsync($webhook, $payload).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw 'Discord rejected the notification.'
        }
    } catch {
        throw "Backup failed ($($failure.Exception.GetType().Name)); Discord notification failed."
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $payload) { $payload.Dispose() }
        $client.Dispose()
    }
    throw "Backup failed ($($failure.Exception.GetType().Name)); Discord notification delivered."
}
