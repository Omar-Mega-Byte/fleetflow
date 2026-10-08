param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][string]$KeyFile,
    [Parameter(Mandatory)][string]$RemoteDestination,
    [Parameter(Mandatory)][string]$DiscordWebhookFile,
    [ValidateRange(1, 365)][int]$RetentionDays = 7
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
