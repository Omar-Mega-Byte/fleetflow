$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envPath = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Local .env is required.' }

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if ($settings['DB_NAME'] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
    $settings['DB_USER'] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
    $settings['OIDC_ISSUER'] -notmatch '^http://127\.0\.0\.1:\d+/realms/fleetflow$') {
    throw 'This smoke flow requires the loopback FleetFlow Compose environment.'
}

$issuer = $settings['OIDC_ISSUER']
$keycloak = $issuer -replace '/realms/fleetflow$', ''
$appPort = if ($settings['APP_PORT']) { $settings['APP_PORT'] } else { '8080' }
$app = "http://127.0.0.1:$appPort"

function Get-AccessToken([string]$realm, [string]$client, [string]$username, [string]$password) {
    $result = Invoke-RestMethod -Uri "$keycloak/realms/$realm/protocol/openid-connect/token" `
        -Method Post -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'password'; client_id = $client; username = $username; password = $password }
    return $result.access_token
}

function Invoke-Sql([string]$sql) {
    $sql | & docker compose --env-file $envPath -p fleetflow exec -T postgres psql `
        -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME'] | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL fixture command failed.' }
}

function New-Actor([string]$role, [string]$adminToken) {
    $username = "phase5-$($role.ToLowerInvariant())-$([guid]::NewGuid())"
    $password = "$([guid]::NewGuid())-$([guid]::NewGuid())"
    $body = @{
        username = $username; email = "$username@example.invalid"; emailVerified = $true
        firstName = 'Phase5'; lastName = $role; enabled = $true; requiredActions = @()
        credentials = @(@{ type = 'password'; value = $password; temporary = $false })
    } | ConvertTo-Json -Depth 5 -Compress
    $created = Invoke-WebRequest -Uri "$keycloak/admin/realms/fleetflow/users" -Method Post `
        -Headers @{ Authorization = "Bearer $adminToken" } -ContentType 'application/json' -Body $body
    if ($created.StatusCode -ne 201) { throw "Keycloak user creation failed for $role." }
    $token = Get-AccessToken 'fleetflow' 'fleetflow-cli' $username $password
    $segment = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    $segment = $segment.PadRight($segment.Length + ((4 - $segment.Length % 4) % 4), '=')
    $subject = ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($segment)) |
        ConvertFrom-Json).sub
    if ($subject -notmatch '^[0-9a-fA-F-]{36}$') { throw 'Unexpected Keycloak subject format.' }
    $actor = [guid]::NewGuid()
    Invoke-Sql "INSERT INTO actors (id, issuer, subject, active, created_at, updated_at) VALUES ('$actor', '$issuer', '$subject', true, now(), now()); INSERT INTO actor_roles (actor_id, role) VALUES ('$actor', '$role');"
    return @{ id = $actor; token = $token }
}

function Send-Request([string]$method, [string]$path, [string]$token, [string]$body = '') {
    $headers = @{ Authorization = "Bearer $token"; 'X-Correlation-ID' = "phase5-$([guid]::NewGuid())" }
    $arguments = @{ Uri = "$app$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true }
    if ($body) { $arguments['ContentType'] = 'application/json'; $arguments['Body'] = $body }
    return Invoke-WebRequest @arguments
}

function New-SmokeOrder([guid]$customerId, [guid]$branchId, [decimal]$latitude, [decimal]$longitude) {
    $id = [guid]::NewGuid()
    $lat = $latitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    $lon = $longitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    Invoke-Sql "INSERT INTO orders (id, customer_id, merchant_branch_id, status, customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, delivery_line1, delivery_city, delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at) VALUES ('$id', '$customerId', '$branchId', 'CREATED', 'Phase5 Customer', 'Phase5 Merchant', 'Smoke Branch', '12 Market Street', 'Cairo', $lat, $lon, '34 Delivery Street', 'Cairo', $lat, $lon, 'EGP', 10.00, now(), now());"
    return $id
}

Push-Location $root
try {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        try {
            $ready = Invoke-WebRequest -Uri "$app/actuator/health/readiness" -SkipHttpErrorCheck
            if ($ready.StatusCode -eq 200) { break }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($ready.StatusCode -ne 200) { throw 'App readiness did not reach HTTP 200.' }
    $adminToken = Get-AccessToken 'master' 'admin-cli' $settings['KC_ADMIN_USER'] $settings['KC_ADMIN_PASSWORD']
    $customer = New-Actor 'CUSTOMER' $adminToken
    $merchant = New-Actor 'MERCHANT' $adminToken
    $dispatcher = New-Actor 'DISPATCHER' $adminToken
    $driverActor = New-Actor 'DRIVER' $adminToken

    $customerId = [guid]::NewGuid(); $merchantId = [guid]::NewGuid()
    $branchId = [guid]::NewGuid(); $driverId = [guid]::NewGuid()
    Invoke-Sql "INSERT INTO customers (id, actor_id, display_name, created_at, updated_at) VALUES ('$customerId', '$($customer.id)', 'Phase5 Customer', now(), now()); INSERT INTO merchants (id, name, active, created_at, updated_at) VALUES ('$merchantId', 'Phase5 Merchant', true, now(), now()); INSERT INTO merchant_branches (id, merchant_id, name, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, active, created_at, updated_at) VALUES ('$branchId', '$merchantId', 'Smoke Branch', '12 Market Street', 'Cairo', 30.000000, 31.000000, true, now(), now()); INSERT INTO merchant_staff_memberships (id, actor_id, branch_id, active, created_at) VALUES ('$([guid]::NewGuid())', '$($merchant.id)', '$branchId', true, now()); INSERT INTO drivers (id, actor_id, active, suspended, status, created_at, updated_at) VALUES ('$driverId', '$($driverActor.id)', true, false, 'OFFLINE', now(), now()); INSERT INTO vehicles (id, driver_id, type, capacity, active, created_at, updated_at) VALUES ('$([guid]::NewGuid())', '$driverId', 'BIKE', 1, true, now(), now());"
    $orderId = New-SmokeOrder $customerId $branchId 30.000000 31.000000

    foreach ($action in @('confirm', 'prepare', 'ready')) {
        $response = Send-Request 'Post' "/api/v1/merchant/orders/$orderId/$action" $merchant.token
        if ($response.StatusCode -ne 204) { throw "Merchant $action returned $($response.StatusCode): $($response.Content)" }
        Write-Host "merchant/$action -> 204"
    }
    foreach ($action in @('online', 'available')) {
        $response = Send-Request 'Post' "/api/v1/drivers/me/$action" $driverActor.token
        if ($response.StatusCode -ne 204) { throw "Driver $action returned $($response.StatusCode): $($response.Content)" }
        Write-Host "driver/$action -> 204"
    }
    $fix = @{ latitude = 30.001; longitude = 31.001; reportedAt = [DateTimeOffset]::UtcNow.ToString('o') } |
        ConvertTo-Json -Compress
    $location = Send-Request 'Post' '/api/v1/drivers/me/location' $driverActor.token $fix
    if ($location.StatusCode -ne 204) { throw "Driver location returned $($location.StatusCode): $($location.Content)" }
    Write-Host 'driver/location -> 204'

    $match = Send-Request 'Post' "/api/v1/dispatch/orders/$orderId/match" $dispatcher.token
    if ($match.StatusCode -ne 201) { throw "Matching returned $($match.StatusCode): $($match.Content)" }
    $matched = $match.Content | ConvertFrom-Json
    if ($matched.outcome -ne 'RANKED') { throw "Matching outcome was $($matched.outcome)." }
    Write-Host "dispatcher/match -> 201 $($matched.outcome)"
    $attempts = Send-Request 'Get' "/api/v1/dispatch/orders/$orderId/attempts" $dispatcher.token
    $view = $attempts.Content | ConvertFrom-Json
    if ($attempts.StatusCode -ne 200 -or $view.content[0].candidates[0].driverId -ne "$driverId" -or
        $view.content[0].candidates[0].rank -ne 1) { throw 'Dispatcher attempt read did not contain the ranked driver.' }
    Write-Host 'dispatcher/attempts -> 200 ranked driver 1'
    $denied = Send-Request 'Get' "/api/v1/dispatch/orders/$orderId/attempts" $customer.token
    if ($denied.StatusCode -ne 403) { throw "Customer attempt read returned $($denied.StatusCode)." }
    Write-Host 'customer/attempts -> 403'
    $customerOrder = Send-Request 'Get' "/api/v1/orders/$orderId" $customer.token
    if ($customerOrder.StatusCode -ne 200 -or $customerOrder.Content -match 'candidates|driverId|score|rank') {
        throw 'Customer order response exposed candidate detail.'
    }
    Write-Host 'customer/order -> 200 without candidate detail'
    Write-Host "Phase 5 local signed-token smoke passed for order $orderId."
}
finally { Pop-Location }
