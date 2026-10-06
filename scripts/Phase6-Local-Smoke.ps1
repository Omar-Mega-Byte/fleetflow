param(
    [switch]$IncludePhase7,
    [switch]$IncludePhase8,
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

function New-Actor([string]$role, [string]$adminToken) {
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
    return @{ id = $actor; token = $token }
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
    $adminToken = Get-AccessToken 'master' 'admin-cli' $settings['KC_ADMIN_USER'] $settings['KC_ADMIN_PASSWORD']
    $customer = New-Actor 'CUSTOMER' $adminToken
    $merchant = New-Actor 'MERCHANT' $adminToken
    $dispatcher = New-Actor 'DISPATCHER' $adminToken
    $driverActor = New-Actor 'DRIVER' $adminToken

    $customerId = [guid]::NewGuid(); $merchantId = [guid]::NewGuid()
    $branchId = [guid]::NewGuid(); $driverId = [guid]::NewGuid()
    Invoke-Sql "INSERT INTO customers (id, actor_id, display_name, created_at, updated_at) VALUES ('$customerId', '$($customer.id)', 'Phase6 Customer', now(), now()); INSERT INTO merchants (id, name, active, created_at, updated_at) VALUES ('$merchantId', 'Phase6 Merchant', true, now(), now()); INSERT INTO merchant_branches (id, merchant_id, name, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, active, created_at, updated_at) VALUES ('$branchId', '$merchantId', 'Smoke Branch', '12 Market Street', 'Cairo', 30.000000, 31.000000, true, now(), now()); INSERT INTO merchant_staff_memberships (id, actor_id, branch_id, active, created_at) VALUES ('$([guid]::NewGuid())', '$($merchant.id)', '$branchId', true, now()); INSERT INTO drivers (id, actor_id, active, suspended, operational_status, created_at, updated_at) VALUES ('$driverId', '$($driverActor.id)', true, false, 'OFFLINE', now(), now()); INSERT INTO vehicles (id, driver_id, type, capacity, active, created_at, updated_at) VALUES ('$([guid]::NewGuid())', '$driverId', 'BIKE', 1, true, now(), now());"
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
    $assignedOrder = Send-Request 'Get' "/api/v1/orders/$orderId" $customer.token
    if ($assignedOrder.StatusCode -ne 200 -or
        ($assignedOrder.Content | ConvertFrom-Json).status -ne 'DRIVER_ASSIGNED' -or
        $assignedOrder.Content -match 'candidates|driverId|score|rank|latitude|longitude') {
        throw 'Assigned customer order response was incorrect or exposed dispatch detail.'
    }
    Write-Host 'customer/order -> 200 DRIVER_ASSIGNED without candidate detail'
    if ($IncludePhase7 -or $IncludePhase8) {
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
    if ($IncludePhase8) { Write-Host "Phase 8 local signed-token smoke passed for order $orderId." }
    elseif ($IncludePhase7) { Write-Host "Phase 7 local signed-token smoke passed for order $orderId." }
    else { Write-Host "Phase 6 local signed-token smoke passed for order $orderId." }
}
finally { Pop-Location }
