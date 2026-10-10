param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(-90, 90)][double]$StartLatitude = 30.0444,
    [ValidateRange(-180, 180)][double]$StartLongitude = 31.2357,
    [ValidateRange(2, 100)][int]$Points = 8,
    [ValidateRange(1, 10)][int]$IntervalSeconds = 2,
    [ValidateRange(0, 120)][int]$PauseSeconds = 0
)

$ErrorActionPreference = 'Stop'
$endpoint = [uri]$BaseUrl
if ($endpoint.Scheme -notin @('http', 'https') -or
    $endpoint.Host -notin @('localhost', '127.0.0.1', '::1') -or
    $endpoint.UserInfo -or $endpoint.Query -or $endpoint.Fragment) {
    throw 'The simulator accepts only a local loopback HTTP(S) base URL.'
}
$token = $env:FLEETFLOW_DRIVER_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw 'Set FLEETFLOW_DRIVER_TOKEN to a signed token for the assigned driver.'
}
$headers = @{ Authorization = "Bearer $token" }
$url = "{0}/api/v1/drivers/me/location" -f $BaseUrl.TrimEnd('/')

for ($point = 0; $point -lt $Points; $point++) {
    if ($point -eq [int][math]::Floor($Points / 2) -and $PauseSeconds -gt 0) {
        Write-Host "Pausing for $PauseSeconds seconds to simulate driver disconnect."
        Start-Sleep -Seconds $PauseSeconds
    }
    $latitude = $StartLatitude + ($point * 0.0002)
    $longitude = $StartLongitude + ($point * 0.0002)
    if ([math]::Abs($latitude) -gt 90 -or [math]::Abs($longitude) -gt 180) {
        throw 'Generated point is outside WGS84 bounds.'
    }
    $body = @{
        latitude = $latitude
        longitude = $longitude
        reportedAt = (Get-Date).ToUniversalTime().ToString('o')
    } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType 'application/json' -Body $body | Out-Null
    Write-Host ("Sent route point {0}/{1}." -f ($point + 1), $Points)
    if ($point -lt $Points - 1) { Start-Sleep -Seconds $IntervalSeconds }
}
Write-Host 'Route complete. No further location or heartbeat updates will be sent.'
