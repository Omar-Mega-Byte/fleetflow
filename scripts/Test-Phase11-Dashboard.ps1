param(
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [switch]$RequireSamples
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = (Resolve-Path -LiteralPath $EnvironmentFile).Path
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if (-not $settings['GRAFANA_ADMIN_USER'] -or -not $settings['GRAFANA_SECRET_FILE']) {
    throw 'Private Grafana username and credential file are required.'
}
$port = if ($settings['GRAFANA_PORT']) { $settings['GRAFANA_PORT'] } else { '3000' }
if ($port -notmatch '^\d{1,5}$' -or [int]$port -lt 1 -or [int]$port -gt 65535) {
    throw 'GRAFANA_PORT must be a loopback TCP port.'
}
$secretPath = $settings['GRAFANA_SECRET_FILE']
if (-not [IO.Path]::IsPathRooted($secretPath)) {
    $secretPath = Join-Path $root $secretPath
}
$secretPath = (Resolve-Path -LiteralPath $secretPath).Path
$secret = (Get-Content -LiteralPath $secretPath -Raw).Trim()
if (-not $secret) { throw 'Grafana credential file is empty.' }
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    $settings['GRAFANA_ADMIN_USER'] + ':' + $secret))
$headers = @{ Authorization = "Basic $basic" }
$base = "http://127.0.0.1:$port"
$health = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 10
if ($health.database -ne 'ok') { throw 'Grafana database is not healthy.' }
$dashboard = (Invoke-RestMethod -Uri "$base/api/dashboards/uid/fleetflow-local-ops" `
    -Headers $headers -TimeoutSec 10).dashboard
if ($dashboard.title -ne 'FleetFlow / local operations' -or $dashboard.panels.Count -ne 12) {
    throw 'The provisioned FleetFlow dashboard is missing or incomplete.'
}
$targets = 0
$sampled = 0
foreach ($panel in $dashboard.panels) {
    foreach ($target in $panel.targets) {
        if (-not $target.expr) { throw "Panel $($panel.id) has an empty query." }
        $query = [uri]::EscapeDataString($target.expr)
        $response = Invoke-RestMethod -Uri `
            "$base/api/datasources/proxy/uid/fleetflow-prometheus/api/v1/query?query=$query" `
            -Headers $headers -TimeoutSec 10
        if ($response.status -ne 'success') { throw "Panel $($panel.id) query failed." }
        $targets++
        if ($response.data.result.Count -gt 0) { $sampled++ }
        elseif ($RequireSamples) { throw "Panel $($panel.id) query returned no series after traffic." }
    }
}
if ($targets -ne 14) { throw "Expected 14 panel queries, found $targets." }
Write-Output "Grafana dashboard checked: 12 panels, $targets successful queries, $sampled with samples."
