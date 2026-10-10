param(
    [switch]$IncludePhase7,
    [switch]$IncludePhase8,
    [switch]$IncludePhase9,
    [switch]$IncludePhase10,
    [switch]$IncludePhase11,
    [switch]$ExerciseBrokerOutage,
    [string]$EnvironmentFile,
    [string]$ComposeProject = 'fleetflow'
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envPath = if ($EnvironmentFile) { (Resolve-Path -LiteralPath $EnvironmentFile).Path }
    else { Join-Path $root '.env' }
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Local .env is required.' }
if ($ComposeProject -notmatch '^[a-z][a-z0-9-]{1,40}$') {
    throw 'ComposeProject must be a lowercase Compose project name.'
}
if ($ExerciseBrokerOutage -and -not $IncludePhase8) {
    throw 'ExerciseBrokerOutage requires IncludePhase8.'
}
if ($IncludePhase11 -and (-not $IncludePhase7 -or -not $IncludePhase8 -or
        -not $IncludePhase9 -or -not $IncludePhase10 -or -not $ExerciseBrokerOutage)) {
    throw 'IncludePhase11 requires all Phase 7-10 and broker outage switches.'
}

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
    $sql | & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql `
        -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME'] | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL fixture command failed.' }
}

function Read-Sql([string]$sql) {
    $result = @($sql | & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql `
        -At -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
    )
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL verification query failed.' }
    return ($result -join "`n").Trim()
}

function New-Actor([string]$role) {
    # Keycloak's admin token can expire during a slow multi-phase smoke run.
    $adminToken = Get-AccessToken 'master' 'admin-cli' $settings['KC_ADMIN_USER'] $settings['KC_ADMIN_PASSWORD']
    $username = "phase6-$($role.ToLowerInvariant())-$([guid]::NewGuid())"
    $password = "$([guid]::NewGuid())-$([guid]::NewGuid())"
    $body = @{
        username = $username; email = "$username@example.invalid"; emailVerified = $true
        firstName = 'Phase6'; lastName = $role; enabled = $true; requiredActions = @()
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
    return @{ id = $actor; token = $token; username = $username; password = $password }
}

function Refresh-ActorToken([hashtable]$actor) {
    $actor.token = Get-AccessToken 'fleetflow' 'fleetflow-cli' $actor.username $actor.password
}

function Send-Request([string]$method, [string]$path, [string]$token, [string]$body = '') {
    $headers = @{ Authorization = "Bearer $token"; 'X-Correlation-ID' = "phase6-$([guid]::NewGuid())" }
    $arguments = @{ Uri = "$app$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true }
    if ($body) { $arguments['ContentType'] = 'application/json'; $arguments['Body'] = $body }
    return Invoke-WebRequest @arguments
}

function New-SmokeOrder([guid]$customerId, [guid]$branchId, [decimal]$latitude, [decimal]$longitude) {
    $id = [guid]::NewGuid()
    $lat = $latitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    $lon = $longitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    Invoke-Sql "INSERT INTO orders (id, customer_id, merchant_branch_id, status, customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, delivery_line1, delivery_city, delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at) VALUES ('$id', '$customerId', '$branchId', 'CREATED', 'Phase6 Customer', 'Phase6 Merchant', 'Smoke Branch', '12 Market Street', 'Cairo', $lat, $lon, '34 Delivery Street', 'Cairo', $lat, $lon, 'EGP', 10.00, now(), now());"
    return $id
}

function New-ReadyOrder([guid]$customerId, [guid]$branchId, [string]$merchantToken) {
    $id = New-SmokeOrder $customerId $branchId 30.000000 31.000000
    foreach ($action in @('confirm', 'start-preparation', 'ready-for-pickup')) {
        $response = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/orders/$id/$action" $merchantToken
        if ($response.StatusCode -ne 200) { throw "Preparing demo order returned $($response.StatusCode)." }
    }
    return $id
}

function Send-Fix([string]$token, [double]$latitude, [double]$longitude) {
    $body = @{ latitude = $latitude; longitude = $longitude;
        reportedAt = [DateTimeOffset]::UtcNow.ToString('o') } | ConvertTo-Json -Compress
    $response = Send-Request 'Post' '/api/v1/drivers/me/location' $token $body
    if ($response.StatusCode -ne 204) { throw "Demo driver fix returned $($response.StatusCode)." }
}

function New-DemoOffer([guid]$orderId, [guid]$driverId, [string]$dispatcherToken) {
    $match = Send-Request 'Post' "/api/v1/dispatch/orders/$orderId/match" $dispatcherToken
    if ($match.StatusCode -ne 201 -or ($match.Content | ConvertFrom-Json).outcome -ne 'RANKED') {
        throw "Demo match returned $($match.StatusCode): $($match.Content)"
    }
    $attempt = ($match.Content | ConvertFrom-Json).attemptId
    $body = @{ attemptId = $attempt; driverId = "$driverId" } | ConvertTo-Json -Compress
    $offer = Send-Request 'Post' "/api/v1/dispatch/orders/$orderId/offers" $dispatcherToken $body
    if ($offer.StatusCode -ne 201) { throw "Demo offer returned $($offer.StatusCode): $($offer.Content)" }
    return ($offer.Content | ConvertFrom-Json).offerId
}

function Complete-DemoDelivery([guid]$orderId, [string]$driverToken) {
    $response = Send-Request 'Get' '/api/v1/drivers/me/deliveries' $driverToken
    $delivery = @((($response.Content | ConvertFrom-Json).content) |
        Where-Object { $_.orderId -eq "$orderId" -and $_.status -eq 'ASSIGNED' })
    if ($response.StatusCode -ne 200 -or $delivery.Count -ne 1) {
        throw 'Demo assigned delivery was not visible to the driver.'
    }
    foreach ($action in @('pickup', 'start-transit', 'complete')) {
        $result = Send-Request 'Post' "/api/v1/drivers/me/deliveries/$($delivery[0].deliveryId)/$action" $driverToken
        if ($result.StatusCode -ne 200) { throw "Demo delivery $action returned $($result.StatusCode)." }
    }
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
    $identityDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    do {
        try {
            $identity = Invoke-WebRequest -Uri "$keycloak/realms/fleetflow/.well-known/openid-configuration" `
                -SkipHttpErrorCheck
            if ($identity.StatusCode -eq 200) { break }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $identityDeadline)
    if ($identity.StatusCode -ne 200) { throw 'Keycloak realm readiness did not reach HTTP 200.' }
    $customer = New-Actor 'CUSTOMER'
    $merchant = New-Actor 'MERCHANT'
    $dispatcher = New-Actor 'DISPATCHER'
    $driverActor = New-Actor 'DRIVER'

    $customerId = [guid]::NewGuid(); $merchantId = [guid]::NewGuid()
    $branchId = [guid]::NewGuid(); $driverId = [guid]::NewGuid()
    Invoke-Sql "INSERT INTO customers (id, actor_id, display_name, created_at, updated_at) VALUES ('$customerId', '$($customer.id)', 'Phase6 Customer', now(), now()); INSERT INTO merchants (id, name, active, created_at, updated_at) VALUES ('$merchantId', 'Phase6 Merchant', true, now(), now()); INSERT INTO merchant_branches (id, merchant_id, name, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, active, created_at, updated_at) VALUES ('$branchId', '$merchantId', 'Smoke Branch', '12 Market Street', 'Cairo', 30.000000, 31.000000, true, now(), now()); INSERT INTO merchant_staff_memberships (id, actor_id, branch_id, active, created_at) VALUES ('$([guid]::NewGuid())', '$($merchant.id)', '$branchId', true, now()); INSERT INTO drivers (id, actor_id, active, suspended, operational_status, created_at, updated_at) VALUES ('$driverId', '$($driverActor.id)', true, false, 'OFFLINE', now(), now()); INSERT INTO vehicles (id, driver_id, type, capacity, active, created_at, updated_at) VALUES ('$([guid]::NewGuid())', '$driverId', 'BIKE', 1, true, now(), now());"
    if ($IncludePhase10) {
        $coreGeometry = '{"type":"Polygon","coordinates":[[[30.9,29.9],[31.1,29.9],[31.1,30.1],[30.9,30.1],[30.9,29.9]]]}' | ConvertFrom-Json
        $zoneBody = @{ name = 'Smoke area'; geometry = $coreGeometry; active = $true } |
            ConvertTo-Json -Depth 10 -Compress
        $createdZone = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/zones" $merchant.token $zoneBody
        if ($createdZone.StatusCode -ne 201) { throw "Zone create returned $($createdZone.StatusCode): $($createdZone.Content)" }
        $zoneId = ($createdZone.Content | ConvertFrom-Json).id
        $policy = Send-Request 'Put' "/api/v1/merchant-branches/$branchId/zone-policy" `
            $merchant.token '{"mode":"ENFORCED","expectedVersion":1}'
        if ($policy.StatusCode -ne 200 -or ($policy.Content | ConvertFrom-Json).version -ne 2) {
            throw "Zone enforcement returned $($policy.StatusCode): $($policy.Content)"
        }
        $inside = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/serviceability" `
            $merchant.token '{"latitude":30,"longitude":31}'
        $outside = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/serviceability" `
            $merchant.token '{"latitude":31,"longitude":31}'
        $deniedZone = Send-Request 'Get' "/api/v1/merchant-branches/$branchId/zones" $customer.token
        if ($inside.StatusCode -ne 200 -or -not ($inside.Content | ConvertFrom-Json).supported -or
            $outside.StatusCode -ne 200 -or ($outside.Content | ConvertFrom-Json).supported -or
            $deniedZone.StatusCode -ne 403) { throw 'Signed serviceability or role scope failed.' }
        Write-Host 'phase10 -> signed zone create, enforce, inside/outside and wrong-role checks passed'
    }
    $orderId = New-SmokeOrder $customerId $branchId 30.000000 31.000000

    foreach ($action in @('confirm', 'prepare', 'ready')) {
        $endpoint = @{ confirm = 'confirm'; prepare = 'start-preparation'; ready = 'ready-for-pickup' }[$action]
        $response = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/orders/$orderId/$endpoint" $merchant.token
        if ($response.StatusCode -ne 200) { throw "Merchant $action returned $($response.StatusCode): $($response.Content)" }
        Write-Host "merchant/$action -> 200"
    }
    foreach ($action in @('online', 'available')) {
        $response = Send-Request 'Post' "/api/v1/drivers/me/$action" $driverActor.token
        if ($response.StatusCode -ne 200) { throw "Driver $action returned $($response.StatusCode): $($response.Content)" }
        Write-Host "driver/$action -> 200"
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
    if ($customerOrder.StatusCode -ne 200 -or
        $customerOrder.Content -match 'candidates|driverId|score|rank|latitude|longitude') {
        throw 'Customer order response exposed dispatch detail or raw coordinates.'
    }
    Write-Host 'customer/order -> 200 without candidate detail'
    $offerBody = @{ attemptId = $matched.attemptId; driverId = "$driverId" } | ConvertTo-Json -Compress
    $offered = Send-Request 'Post' "/api/v1/dispatch/orders/$orderId/offers" $dispatcher.token $offerBody
    if ($offered.StatusCode -ne 201) { throw "Offer returned $($offered.StatusCode): $($offered.Content)" }
    $offerId = ($offered.Content | ConvertFrom-Json).offerId
    Write-Host 'dispatcher/offers -> 201 OFFERED'
    $mine = Send-Request 'Get' '/api/v1/drivers/me/offers' $driverActor.token
    if ($mine.StatusCode -ne 200 -or ($mine.Content | ConvertFrom-Json).content[0].offerId -ne "$offerId") {
        throw 'Offered driver did not see their own offer.'
    }
    Write-Host 'driver/offers -> 200 own offer'
    $private = Send-Request 'Get' '/api/v1/drivers/me/offers' $customer.token
    if ($private.StatusCode -ne 403) { throw "Customer offer read returned $($private.StatusCode)." }
    Write-Host 'customer/offers -> 403'
    $accepted = Send-Request 'Post' "/api/v1/drivers/me/offers/$offerId/accept" $driverActor.token
    if ($accepted.StatusCode -ne 200 -or ($accepted.Content | ConvertFrom-Json).status -ne 'ACCEPTED') {
        throw "Offer acceptance returned $($accepted.StatusCode): $($accepted.Content)"
    }
    Write-Host 'driver/accept -> 200 ACCEPTED'
    $duplicate = Send-Request 'Post' "/api/v1/drivers/me/offers/$offerId/accept" $driverActor.token
    if ($duplicate.StatusCode -ne 409) { throw "Duplicate accept returned $($duplicate.StatusCode)." }
    Write-Host 'driver/duplicate accept -> 409'
    $sql = "select a.status || '|' || d.operational_status || '|' || o.status || '|' || dl.status from driver_assignments a join drivers d on d.id = a.driver_id join orders o on o.id = a.order_id join deliveries dl on dl.accepted_assignment_id = a.id where a.id = '$offerId';"
    $tuple = $sql | & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql -At -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
    if ($LASTEXITCODE -ne 0 -or $tuple.Trim() -ne 'ACCEPTED|DELIVERING|DRIVER_ASSIGNED|ASSIGNED') {
        throw "Durable assignment tuple was invalid: $tuple"
    }
    Write-Host 'postgres -> ACCEPTED|DELIVERING|DRIVER_ASSIGNED|ASSIGNED'
    if ($IncludePhase10) {
        $farGeometry = '{"type":"Polygon","coordinates":[[[33,30],[34,30],[34,31],[33,31],[33,30]]]}' | ConvertFrom-Json
        $replaceBody = @{ name = 'Far'; geometry = $farGeometry; active = $true; expectedVersion = 0 } |
            ConvertTo-Json -Depth 10 -Compress
        $updatedZone = Send-Request 'Put' "/api/v1/merchant-branches/$branchId/zones/$zoneId" `
            $merchant.token $replaceBody
        if ($updatedZone.StatusCode -ne 200) { throw "Zone update returned $($updatedZone.StatusCode): $($updatedZone.Content)" }
        $blockedOrder = New-SmokeOrder $customerId $branchId 30.000000 31.000000
        foreach ($action in @('confirm', 'start-preparation', 'ready-for-pickup')) {
            $response = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/orders/$blockedOrder/$action" $merchant.token
            if ($response.StatusCode -ne 200) { throw "Blocked order $action returned $($response.StatusCode)." }
        }
        $blockedMatch = Send-Request 'Post' "/api/v1/dispatch/orders/$blockedOrder/match" $dispatcher.token
        if ($blockedMatch.StatusCode -ne 201 -or
            ($blockedMatch.Content | ConvertFrom-Json).outcome -ne 'ZONE_UNSERVICEABLE') {
            throw "Zone-blocked dispatch returned $($blockedMatch.StatusCode): $($blockedMatch.Content)"
        }
        $incident = Send-Request 'Get' "/api/v1/dispatch/orders/$blockedOrder/incidents" $dispatcher.token
        if ($incident.StatusCode -ne 200 -or
            ($incident.Content | ConvertFrom-Json).content[0].code -ne 'ZONE_UNSERVICEABLE') {
            throw 'Zone-blocked dispatch had no durable incident.'
        }
        $acceptedAfterEdit = Send-Request 'Get' "/api/v1/orders/$orderId" $customer.token
        if ($acceptedAfterEdit.StatusCode -ne 200 -or
            ($acceptedAfterEdit.Content | ConvertFrom-Json).status -ne 'DRIVER_ASSIGNED') {
            throw 'Accepted assignment changed after a zone edit.'
        }
        Write-Host 'phase10 -> changed zone blocked dispatch; incident persisted; accepted work retained'
    }
    $assignedOrder = Send-Request 'Get' "/api/v1/orders/$orderId" $customer.token
    if ($assignedOrder.StatusCode -ne 200 -or
        ($assignedOrder.Content | ConvertFrom-Json).status -ne 'DRIVER_ASSIGNED' -or
        $assignedOrder.Content -match 'candidates|driverId|score|rank|latitude|longitude') {
        throw 'Assigned customer order response was incorrect or exposed dispatch detail.'
    }
    Write-Host 'customer/order -> 200 DRIVER_ASSIGNED without candidate detail'
    if ($IncludePhase9) {
        $trackingPath = "/api/v1/orders/$orderId/tracking"
        $snapshotResponse = Send-Request 'Get' $trackingPath $customer.token
        $snapshot = $snapshotResponse.Content | ConvertFrom-Json
        if ($snapshotResponse.StatusCode -ne 200 -or $snapshot.orderStatus -ne 'DRIVER_ASSIGNED' -or
            $snapshot.position.latitude -ne 30.001 -or $snapshotResponse.Content -match 'driverId|assignmentId') {
            throw 'Owner tracking snapshot was missing the accepted fresh position or exposed internal IDs.'
        }
        $otherCustomer = New-Actor 'CUSTOMER'
        $foreign = Send-Request 'Get' $trackingPath $otherCustomer.token
        if ($foreign.StatusCode -ne 404) { throw 'Foreign customer could access tracking.' }

        $streamJob = Start-ThreadJob -ArgumentList $app, $orderId, $customer.token -ScriptBlock {
            param($base, $order, $token)
            $client = [System.Net.Http.HttpClient]::new()
            $request = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::Get, "$base/api/v1/orders/$order/tracking/stream")
            $request.Headers.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
            $request.Headers.Accept.ParseAdd('text/event-stream')
            try {
                $response = $client.SendAsync($request,
                    [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
                if (-not $response.IsSuccessStatusCode) { throw 'Tracking stream rejected the owner.' }
                $reader = [System.IO.StreamReader]::new(
                    $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                $event = ''
                while ($true) {
                    $line = $reader.ReadLineAsync().WaitAsync(
                        [TimeSpan]::FromSeconds(20)).GetAwaiter().GetResult()
                    if ($null -eq $line) { break }
                    if ($line.StartsWith('event:')) { $event = $line.Substring(6).Trim() }
                    if ($line.StartsWith('data:') -and $event -eq 'snapshot') { Write-Output 'SNAPSHOT' }
                    if ($line.StartsWith('data:') -and $event -eq 'tracking' -and
                        $line.Contains('"latitude":30.002')) { Write-Output 'MOVED'; break }
                }
            }
            finally {
                if ($reader) { $reader.Dispose() }
                if ($response) { $response.Dispose() }
                $request.Dispose()
                $client.Dispose()
            }
        }
        try {
            $streamDeadline = [DateTimeOffset]::UtcNow.AddSeconds(10)
            do {
                $seen = @(Receive-Job -Job $streamJob -Keep)
                if ($seen -contains 'SNAPSHOT') { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTimeOffset]::UtcNow -lt $streamDeadline)
            if ($seen -notcontains 'SNAPSHOT') { throw 'SSE did not start with a snapshot.' }
            $nextFix = @{ latitude = 30.002; longitude = 31.002
                reportedAt = [DateTimeOffset]::UtcNow.ToString('o') } | ConvertTo-Json -Compress
            $moved = Send-Request 'Post' '/api/v1/drivers/me/location' $driverActor.token $nextFix
            if ($moved.StatusCode -ne 204) { throw 'Driver movement was rejected.' }
            $null = Wait-Job -Job $streamJob -Timeout 20
            $seen = @(Receive-Job -Job $streamJob -Keep)
            if ($streamJob.State -ne 'Completed' -or $seen -notcontains 'MOVED') {
                throw 'SSE did not deliver accepted driver movement.'
            }
        }
        finally {
            if ($streamJob.State -eq 'Running') { Stop-Job -Job $streamJob }
            Remove-Job -Job $streamJob -Force
        }
        $previousDriverToken = $env:FLEETFLOW_DRIVER_TOKEN
        try {
            $env:FLEETFLOW_DRIVER_TOKEN = $driverActor.token
            & (Join-Path $PSScriptRoot 'Simulate-Driver-Route.ps1') -BaseUrl $app `
                -StartLatitude 30.003 -StartLongitude 31.003 -Points 2 -IntervalSeconds 1
            if (-not $?) { throw 'Driver simulator failed.' }
        }
        finally { $env:FLEETFLOW_DRIVER_TOKEN = $previousDriverToken }
        $afterRoute = Send-Request 'Get' $trackingPath $customer.token
        if ($afterRoute.StatusCode -ne 200 -or
            ($afterRoute.Content | ConvertFrom-Json).position.latitude -ne 30.0032) {
            throw 'Simulator movement was not visible in customer tracking.'
        }
        Write-Host 'phase9 -> signed owner snapshot, foreign 404, SSE movement and simulator verified'
    }
    if ($IncludePhase7 -or $IncludePhase8 -or $IncludePhase9) {
        $deliveries = Send-Request 'Get' '/api/v1/drivers/me/deliveries' $driverActor.token
        if ($deliveries.StatusCode -ne 200) { throw "Delivery read returned $($deliveries.StatusCode)." }
        $delivery = @((($deliveries.Content | ConvertFrom-Json).content) |
            Where-Object { $_.orderId -eq "$orderId" -and $_.status -eq 'ASSIGNED' })
        if ($delivery.Count -ne 1) { throw 'Assigned delivery was not visible to its driver.' }
        $deliveryId = $delivery[0].deliveryId
        foreach ($action in @('pickup', 'start-transit', 'complete')) {
            $result = Send-Request 'Post' "/api/v1/drivers/me/deliveries/$deliveryId/$action" $driverActor.token
            if ($result.StatusCode -ne 200) { throw "Delivery $action returned $($result.StatusCode): $($result.Content)" }
            Write-Host "driver/delivery/$action -> 200"
        }
        $finalOrder = Send-Request 'Get' "/api/v1/orders/$orderId" $customer.token
        if ($finalOrder.StatusCode -ne 200 -or
            ($finalOrder.Content | ConvertFrom-Json).status -ne 'DELIVERED') {
            throw 'Customer did not see DELIVERED.'
        }
        $finalTuple = "select a.status || '|' || d.operational_status || '|' || o.status || '|' || dl.status from driver_assignments a join drivers d on d.id = a.driver_id join orders o on o.id = a.order_id join deliveries dl on dl.accepted_assignment_id = a.id where a.id = '$offerId';" |
            & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql -At -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
        if ($LASTEXITCODE -ne 0 -or $finalTuple.Trim() -ne 'COMPLETED|AVAILABLE|DELIVERED|DELIVERED') {
            throw "Final durable delivery tuple was invalid: $finalTuple"
        }
        Write-Host 'postgres -> COMPLETED|AVAILABLE|DELIVERED|DELIVERED'
        if ($IncludePhase9) {
            $terminalTracking = Send-Request 'Get' "/api/v1/orders/$orderId/tracking" $customer.token
            $finalSnapshot = $terminalTracking.Content | ConvertFrom-Json
            if ($terminalTracking.StatusCode -ne 200 -or -not $finalSnapshot.terminal -or
                $null -ne $finalSnapshot.position) { throw 'Terminal tracking retained a live position.' }
            Write-Host 'phase9 -> terminal snapshot hides coordinates'
        }
    }
    if ($IncludePhase8) {
        $eventDeadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
        do {
            $pending = "select count(*) from outbox_events where aggregate_id = '$orderId' and status <> 'PUBLISHED';" |
                & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql -At `
                    -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
            if ($LASTEXITCODE -ne 0) { throw 'Outbox query failed.' }
            if ($pending.Trim() -eq '0') { break }
            Start-Sleep -Milliseconds 500
        } while ([DateTimeOffset]::UtcNow -lt $eventDeadline)
        if ($pending.Trim() -ne '0') { throw "Outbox did not drain for order $orderId." }
        $expectedStates = @('CONFIRMED', 'READY_FOR_PICKUP', 'DRIVER_ASSIGNED',
            'PICKED_UP', 'IN_TRANSIT', 'DELIVERED')
        $projectionDeadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
        do {
            $customerNotices = Send-Request 'Get' '/api/v1/notifications/me?limit=20' $customer.token
            $driverNotices = Send-Request 'Get' '/api/v1/notifications/me?limit=20' $driverActor.token
            $notices = @((($customerNotices.Content | ConvertFrom-Json).content) |
                Where-Object { $_.orderId -eq "$orderId" })
            $offers = @((($driverNotices.Content | ConvertFrom-Json).content) |
                Where-Object { $_.orderId -eq "$orderId" -and $_.kind -eq 'ASSIGNMENT_OFFERED' })
            if ($customerNotices.StatusCode -eq 200 -and $driverNotices.StatusCode -eq 200 -and
                $offers.Count -eq 1 -and @($expectedStates | Where-Object { $_ -notin $notices.state }).Count -eq 0) {
                break
            }
            Start-Sleep -Milliseconds 500
        } while ([DateTimeOffset]::UtcNow -lt $projectionDeadline)
        if ($offers.Count -ne 1 -or @($expectedStates | Where-Object { $_ -notin $notices.state }).Count -ne 0) {
            throw 'Phase 8 notification projections did not contain the expected order and offer events.'
        }
        if (@($notices | Where-Object { $_.kind -eq 'ASSIGNMENT_OFFERED' }).Count -ne 0 -or
            @((($driverNotices.Content | ConvertFrom-Json).content) |
                Where-Object { $_.orderId -eq "$orderId" -and $_.kind -eq 'ORDER_STATUS_CHANGED' }).Count -ne 0) {
            throw 'Notification recipient scopes were mixed.'
        }
        $today = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
        $report = Send-Request 'Get' "/api/v1/dispatch/analytics/daily?from=$today&to=$today" $dispatcher.token
        if ($report.StatusCode -ne 200 -or
            @((($report.Content | ConvertFrom-Json).content) |
                Where-Object { $_.status -eq 'DELIVERED' -and $_.total -ge 1 }).Count -ne 1) {
            throw 'Dispatcher analytics report did not contain the delivered transition.'
        }
        $deniedReport = Send-Request 'Get' "/api/v1/dispatch/analytics/daily?from=$today&to=$today" $customer.token
        if ($deniedReport.StatusCode -ne 403) { throw 'Customer could read dispatch analytics.' }
        $backlog = Send-Request 'Get' '/actuator/metrics/fleetflow.outbox.pending' $dispatcher.token
        if ($backlog.StatusCode -ne 200) {
            throw "Dispatcher metric read returned $($backlog.StatusCode): $($backlog.Content)"
        }
        Write-Host 'phase8 -> outbox drained, signed notification scopes and UTC report verified'
        if ($ExerciseBrokerOutage) {
            $outageOrder = $null
            try {
                & docker compose --env-file $envPath -p $ComposeProject stop kafka | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Stopping the isolated Kafka broker failed.' }
                $outageOrder = New-SmokeOrder $customerId $branchId 30.000000 31.000000
                $duringOutage = Send-Request 'Post' "/api/v1/merchant-branches/$branchId/orders/$outageOrder/confirm" `
                    $merchant.token
                if ($duringOutage.StatusCode -ne 200) {
                    throw "Order confirmation during broker outage returned $($duringOutage.StatusCode)."
                }
                $duringSql = "select o.status || '|' || e.status from orders o join outbox_events e on e.aggregate_id = o.id where o.id = '$outageOrder';" |
                    & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql -At `
                        -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
                if ($LASTEXITCODE -ne 0 -or $duringSql.Trim() -ne 'CONFIRMED|PENDING') {
                    throw "Broker outage did not leave a committed order and pending event: $duringSql"
                }
                Write-Host 'phase8 -> broker down, order CONFIRMED with durable PENDING event'
            }
            finally {
                & docker compose --env-file $envPath -p $ComposeProject start kafka | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Restarting the isolated Kafka broker failed.' }
            }
            $recoveryDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
            do {
                $recovered = "select e.status || '|' || count(p.event_id) from outbox_events e left join processed_events p on p.event_id = e.id where e.aggregate_id = '$outageOrder' group by e.status;" |
                    & docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql -At `
                        -v ON_ERROR_STOP=1 -U $settings['DB_USER'] -d $settings['DB_NAME']
                if ($LASTEXITCODE -ne 0) { throw 'Broker recovery query failed.' }
                if ($recovered.Trim() -eq 'PUBLISHED|2') { break }
                Start-Sleep -Seconds 1
            } while ([DateTimeOffset]::UtcNow -lt $recoveryDeadline)
            if ($recovered.Trim() -ne 'PUBLISHED|2') {
                throw "Broker recovery did not drain and project the event: $recovered"
            }
            Write-Host 'phase8 -> broker restored, event PUBLISHED with two processed markers'
        }
    }
    if ($IncludePhase11) {
        foreach ($actor in @($customer, $merchant, $dispatcher, $driverActor)) {
            Refresh-ActorToken $actor
        }
        $restoreZone = @{ name = 'Smoke area'; geometry = $coreGeometry; active = $true;
            expectedVersion = ($updatedZone.Content | ConvertFrom-Json).version } |
            ConvertTo-Json -Depth 10 -Compress
        $restoredZone = Send-Request 'Put' "/api/v1/merchant-branches/$branchId/zones/$zoneId" `
            $merchant.token $restoreZone
        if ($restoredZone.StatusCode -ne 200) { throw 'Demo zone reset failed.' }

        $driverTwo = New-Actor 'DRIVER'
        $driverTwoId = [guid]::NewGuid()
        Invoke-Sql "INSERT INTO drivers (id, actor_id, active, suspended, operational_status, created_at, updated_at) VALUES ('$driverTwoId', '$($driverTwo.id)', true, false, 'OFFLINE', now(), now()); INSERT INTO vehicles (id, driver_id, type, capacity, active, created_at, updated_at) VALUES ('$([guid]::NewGuid())', '$driverTwoId', 'BIKE', 1, true, now(), now());"
        foreach ($action in @('online', 'available')) {
            $response = Send-Request 'Post' "/api/v1/drivers/me/$action" $driverTwo.token
            if ($response.StatusCode -ne 200) { throw "Second driver $action failed." }
        }
        Send-Fix $driverActor.token 30.001 31.001
        Send-Fix $driverTwo.token 30.005 31.005

        $rejectedOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $firstOffer = New-DemoOffer $rejectedOrder $driverId $dispatcher.token
        $firstRank = ((Send-Request 'Get' "/api/v1/dispatch/orders/$rejectedOrder/attempts" `
            $dispatcher.token).Content | ConvertFrom-Json).content[0].candidates[0].driverId
        if ($firstRank -ne "$driverId") { throw 'The first demo driver did not rank first.' }
        Send-Fix $driverTwo.token 30.005 31.005
        $rejection = Send-Request 'Post' "/api/v1/drivers/me/offers/$firstOffer/reject" $driverActor.token
        if ($rejection.StatusCode -ne 200) { throw 'First driver rejection failed.' }
        $nextOffer = $null
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
        do {
            $nextOffer = Read-Sql "select id::text from driver_assignments where order_id = '$rejectedOrder' and driver_id = '$driverTwoId' and status = 'OFFERED' order by offered_at desc limit 1"
            if ($nextOffer) { break }
            Start-Sleep -Seconds 1
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        if (-not $nextOffer) { throw 'Rejection did not advance to the next ranked driver.' }
        $nextAcceptance = Send-Request 'Post' "/api/v1/drivers/me/offers/$nextOffer/accept" $driverTwo.token
        if ($nextAcceptance.StatusCode -ne 200) { throw 'Next ranked driver could not accept.' }
        Complete-DemoDelivery $rejectedOrder $driverTwo.token
        Write-Host 'phase11 -> rejection advanced to next ranked driver and completed'

        Send-Fix $driverActor.token 30.001 31.001
        Send-Fix $driverTwo.token 30.005 31.005
        $timeoutOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $timeoutOffer = New-DemoOffer $timeoutOrder $driverId $dispatcher.token
        Invoke-Sql "update driver_assignments set offered_at = clock_timestamp() - interval '2 minute', expires_at = clock_timestamp() - interval '1 minute' where id = '$timeoutOffer'"
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
        $timeoutStatus = ''
        $timeoutNext = ''
        do {
            $timeoutStatus = Read-Sql "select status from driver_assignments where id = '$timeoutOffer'"
            $timeoutNext = Read-Sql "select id::text from driver_assignments where order_id = '$timeoutOrder' and driver_id = '$driverTwoId' and status = 'OFFERED' order by offered_at desc limit 1"
            if ($timeoutStatus -eq 'TIMED_OUT' -and $timeoutNext) { break }
            Start-Sleep -Seconds 1
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        if ($timeoutStatus -ne 'TIMED_OUT' -or -not $timeoutNext) {
            throw 'Timed-out offer did not release and advance to the second driver.'
        }
        $timeoutReject = Send-Request 'Post' "/api/v1/drivers/me/offers/$timeoutNext/reject" $driverTwo.token
        if ($timeoutReject.StatusCode -ne 200) { throw 'Second timeout-scenario driver rejection failed.' }
        Write-Host 'phase11 -> expired offer timed out and next candidate was offered'

        foreach ($actor in @($customer, $merchant, $dispatcher, $driverActor, $driverTwo)) {
            Refresh-ActorToken $actor
        }

        Send-Fix $driverTwo.token 30.005 31.005
        & docker compose --env-file $envPath -p $ComposeProject exec -T redis redis-cli DEL `
            "fleetflow:driver:heartbeat:$driverId" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not expire the synthetic driver heartbeat.' }
        $staleOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $staleMatch = Send-Request 'Post' "/api/v1/dispatch/orders/$staleOrder/match" $dispatcher.token
        $staleCandidates = @(((Send-Request 'Get' "/api/v1/dispatch/orders/$staleOrder/attempts" `
            $dispatcher.token).Content | ConvertFrom-Json).content[0].candidates)
        $staleEvidence = @($staleCandidates | Where-Object {
            $_.driverId -eq "$driverId" -and $_.reason -eq 'STALE_HEARTBEAT' -and $null -eq $_.rank
        })
        $freshEvidence = @($staleCandidates | Where-Object {
            $_.driverId -eq "$driverTwoId" -and $_.reason -eq 'ELIGIBLE' -and $_.rank -eq 1
        })
        if ($staleMatch.StatusCode -ne 201 -or $staleEvidence.Count -ne 1 -or
                $freshEvidence.Count -ne 1) {
            throw 'Stale driver was not excluded from eligible ranking.'
        }
        Write-Host 'phase11 -> stale driver excluded from ranking'

        Send-Fix $driverActor.token 30.001 31.001
        Send-Fix $driverTwo.token 30.005 31.005
        $raceOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $raceMatch = Send-Request 'Post' "/api/v1/dispatch/orders/$raceOrder/match" $dispatcher.token
        if ($raceMatch.StatusCode -ne 201) { throw 'Concurrent demo match failed.' }
        $raceBody = @{ attemptId = ($raceMatch.Content | ConvertFrom-Json).attemptId;
            driverId = "$driverId" } | ConvertTo-Json -Compress
        $jobs = @(1..2 | ForEach-Object {
            Start-ThreadJob -ArgumentList $app, $raceOrder, $dispatcher.token, $raceBody -ScriptBlock {
                param($base, $id, $token, $body)
                Start-Sleep -Milliseconds 500
                $result = Invoke-WebRequest -Uri "$base/api/v1/dispatch/orders/$id/offers" -Method Post `
                    -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' `
                    -Body $body -SkipHttpErrorCheck
                return [int]$result.StatusCode
            }
        })
        try {
            $null = $jobs | Wait-Job -Timeout 20
            $statuses = @($jobs | Receive-Job)
            if (@($statuses | Where-Object { $_ -eq 201 }).Count -ne 1 -or
                    @($statuses | Where-Object { $_ -eq 409 }).Count -ne 1) {
                throw "Concurrent offers did not have exactly one winner: $statuses"
            }
        } finally { $jobs | Remove-Job -Force }
        $raceCancelled = Send-Request 'Post' "/api/v1/dispatch/orders/$raceOrder/cancel" `
            $dispatcher.token '{"reason":"Synthetic race cleanup"}'
        if ($raceCancelled.StatusCode -ne 200) { throw 'Concurrent demo cleanup failed.' }
        Write-Host 'phase11 -> concurrent offers had one 201 winner and one 409 conflict'

        Send-Fix $driverActor.token 30.001 31.001
        $failedOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $failedOffer = New-DemoOffer $failedOrder $driverId $dispatcher.token
        $failedAcceptance = Send-Request 'Post' "/api/v1/drivers/me/offers/$failedOffer/accept" $driverActor.token
        if ($failedAcceptance.StatusCode -ne 200) { throw 'Failed-delivery offer acceptance failed.' }
        $failedDeliveries = Send-Request 'Get' '/api/v1/drivers/me/deliveries' $driverActor.token
        $failedDelivery = @((($failedDeliveries.Content | ConvertFrom-Json).content) |
            Where-Object { $_.orderId -eq "$failedOrder" -and $_.status -eq 'ASSIGNED' })
        if ($failedDelivery.Count -ne 1) { throw 'Failed delivery was not assigned.' }
        $pickup = Send-Request 'Post' "/api/v1/drivers/me/deliveries/$($failedDelivery[0].deliveryId)/pickup" $driverActor.token
        $failure = Send-Request 'Post' "/api/v1/drivers/me/deliveries/$($failedDelivery[0].deliveryId)/fail" `
            $driverActor.token '{"reason":"Synthetic vehicle failure"}'
        $incidents = Send-Request 'Get' "/api/v1/dispatch/orders/$failedOrder/incidents" $dispatcher.token
        if ($pickup.StatusCode -ne 200 -or $failure.StatusCode -ne 200 -or
                $incidents.StatusCode -ne 200 -or
                ($incidents.Content | ConvertFrom-Json).content[0].code -ne 'DELIVERY_FAILED') {
            throw 'Failed delivery did not create a dispatcher-visible incident.'
        }
        Write-Host 'phase11 -> failed delivery produced an operator-visible incident'

        Send-Fix $driverActor.token 30.001 31.001
        $redisOrder = New-ReadyOrder $customerId $branchId $merchant.token
        $redisOffer = New-DemoOffer $redisOrder $driverId $dispatcher.token
        $redisAcceptance = Send-Request 'Post' "/api/v1/drivers/me/offers/$redisOffer/accept" $driverActor.token
        if ($redisAcceptance.StatusCode -ne 200) { throw 'Redis scenario acceptance failed.' }
        $tracking = "/api/v1/orders/$redisOrder/tracking"
        $beforeRedis = Send-Request 'Get' $tracking $customer.token
        if ($beforeRedis.StatusCode -ne 200 -or
                $null -eq ($beforeRedis.Content | ConvertFrom-Json).position) {
            throw 'Fresh tracking position was unavailable before Redis outage.'
        }
        try {
            & docker compose --env-file $envPath -p $ComposeProject stop redis | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Stopping synthetic Redis failed.' }
            $duringRedis = Send-Request 'Get' $tracking $customer.token
            if ($duringRedis.StatusCode -ne 200 -or
                    $null -ne ($duringRedis.Content | ConvertFrom-Json).position) {
                throw 'Tracking exposed a location during Redis outage.'
            }
        } finally {
            & docker compose --env-file $envPath -p $ComposeProject start redis | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Restarting synthetic Redis failed.' }
        }
        $redisReady = $false
        for ($i = 0; $i -lt 30; $i++) {
            & docker compose --env-file $envPath -p $ComposeProject exec -T redis redis-cli ping | Out-Null
            if ($LASTEXITCODE -eq 0) { $redisReady = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $redisReady) { throw 'Redis did not recover.' }
        Send-Fix $driverActor.token 30.001 31.001
        $afterRedis = Send-Request 'Get' $tracking $customer.token
        if ($afterRedis.StatusCode -ne 200 -or
                $null -eq ($afterRedis.Content | ConvertFrom-Json).position) {
            throw 'Fresh tracking position was not restored after Redis restart.'
        }
        $redisCancelled = Send-Request 'Post' "/api/v1/dispatch/orders/$redisOrder/cancel" `
            $dispatcher.token '{"reason":"Synthetic reconnect cleanup"}'
        if ($redisCancelled.StatusCode -ne 200) { throw 'Redis demo cleanup failed.' }
        Write-Host 'phase11 -> Redis outage hid location; fresh reconnect restored tracking'
    }
    if ($IncludePhase11) { Write-Host "Phase 11 signed demo scenarios passed in $ComposeProject." }
    elseif ($IncludePhase10) { Write-Host "Phase 10 local signed-token smoke passed for order $orderId." }
    elseif ($IncludePhase9) { Write-Host "Phase 9 local signed-token smoke passed for order $orderId." }
    elseif ($IncludePhase8) { Write-Host "Phase 8 local signed-token smoke passed for order $orderId." }
    elseif ($IncludePhase7) { Write-Host "Phase 7 local signed-token smoke passed for order $orderId." }
    else { Write-Host "Phase 6 local signed-token smoke passed for order $orderId." }
}
finally { Pop-Location }
