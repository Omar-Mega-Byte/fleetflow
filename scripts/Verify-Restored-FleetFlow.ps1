param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvironmentFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Project -cnotmatch '^[a-z][a-z0-9-]{1,40}$') { throw 'Invalid Compose project name.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = (Resolve-Path -LiteralPath $EnvironmentFile).Path
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
foreach ($key in @('DB_USER', 'DB_NAME', 'KC_ADMIN_USER', 'KC_ADMIN_PASSWORD', 'OIDC_ISSUER')) {
    if (-not $settings[$key]) { throw "Missing $key in private environment file." }
}
if ($settings['DB_USER'] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
        $settings['DB_NAME'] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
        $settings['OIDC_ISSUER'] -notmatch '^http://127\.0\.0\.1:\d+/realms/fleetflow$') {
    throw 'This verification requires the local FleetFlow identity boundary.'
}
$composeArgs = @('compose', '--env-file', $envPath, '-p', $Project,
    '-f', (Join-Path $root 'compose.yaml'))
$sql = "select a.subject::text || '|' || o.id::text from actors a join customers c on c.actor_id = a.id join orders o on o.customer_id = c.id where o.status = 'CONFIRMED' order by o.created_at desc limit 1"
$row = ($sql | & docker @composeArgs exec -T postgres psql -At -v ON_ERROR_STOP=1 `
    -U $settings['DB_USER'] -d $settings['DB_NAME']).Trim()
if ($LASTEXITCODE -ne 0 -or $row -notmatch '^([0-9a-fA-F-]{36})\|([0-9a-fA-F-]{36})$') {
    throw 'A restored CONFIRMED synthetic customer order is required.'
}
$subject = $Matches[1]
$orderId = $Matches[2]
$issuer = $settings['OIDC_ISSUER']
$keycloak = $issuer -replace '/realms/fleetflow$', ''
$admin = Invoke-RestMethod -Method Post -Uri "$keycloak/realms/master/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type='password'; client_id='admin-cli'; username=$settings['KC_ADMIN_USER'];
        password=$settings['KC_ADMIN_PASSWORD']
    }
$adminHeaders = @{ Authorization = "Bearer $($admin.access_token)" }
$user = Invoke-RestMethod -Method Get -Uri "$keycloak/admin/realms/fleetflow/users/$subject" `
    -Headers $adminHeaders
if ($user.id -ne $subject -or -not $user.enabled) { throw 'Restored Keycloak subject is missing or disabled.' }

# This changes only the disposable restored user's synthetic password; the subject stays the same.
$password = "$([guid]::NewGuid())-$([guid]::NewGuid())"
$reset = Invoke-WebRequest -Method Put -Uri "$keycloak/admin/realms/fleetflow/users/$subject/reset-password" `
    -Headers $adminHeaders -ContentType 'application/json' -SkipHttpErrorCheck `
    -Body (@{ type='password'; value=$password; temporary=$false } | ConvertTo-Json -Compress)
if ($reset.StatusCode -ne 204) { throw "Restored user password reset returned $($reset.StatusCode)." }
$token = Invoke-RestMethod -Method Post -Uri "$keycloak/realms/fleetflow/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type='password'; client_id='fleetflow-cli'; username=$user.username; password=$password
    }
$headers = @{ Authorization = "Bearer $($token.access_token)";
    'X-Correlation-ID' = "phase11-restore-$([guid]::NewGuid())" }
$appPort = if ($settings['APP_PORT']) { $settings['APP_PORT'] } else { '8080' }
$app = "http://127.0.0.1:$appPort"
$read = Invoke-WebRequest -Method Get -Uri "$app/api/v1/orders/$orderId" `
    -Headers $headers -SkipHttpErrorCheck
if ($read.StatusCode -ne 200 -or ($read.Content | ConvertFrom-Json).id -ne $orderId) {
    throw "Signed restored order read returned $($read.StatusCode)."
}
$command = Invoke-WebRequest -Method Post -Uri "$app/api/v1/orders/$orderId/cancel" `
    -Headers $headers -ContentType 'application/json' -Body '{"reason":"Synthetic restore rehearsal"}' `
    -SkipHttpErrorCheck
if ($command.StatusCode -ne 200 -or ($command.Content | ConvertFrom-Json).status -ne 'CANCELLED') {
    throw "Signed restored order cancellation returned $($command.StatusCode)."
}
$verifySql = "select status from orders where id = '$orderId'"
$stored = ($verifySql | & docker @composeArgs exec -T postgres psql -At -v ON_ERROR_STOP=1 `
    -U $settings['DB_USER'] -d $settings['DB_NAME']).Trim()
if ($LASTEXITCODE -ne 0 -or $stored -ne 'CANCELLED') { throw 'Restored command did not persist.' }
Write-Output 'Keycloak restored user ID matched the PostgreSQL actor subject.'
Write-Output "Signed read: 200 order $orderId"
Write-Output 'Signed cancel: 200; PostgreSQL status CANCELLED.'
