param([string]$EvidenceDirectory)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$scratchRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$sourceProject = "fleetflow-day2-backup-$suffix"
$targetProject = "fleetflow-day2-restore-$suffix"
$scratch = Join-Path $scratchRoot "fleetflow-day2-backup-$suffix"
$evidenceRoot = if ($EvidenceDirectory) { [IO.Path]::GetFullPath($EvidenceDirectory) }
    else { Join-Path $env:LOCALAPPDATA ("FleetFlowDay2Audit\" + [DateTime]::UtcNow.ToString('yyyy-MM-dd') + '\backup-restore') }
$archiveRoot = Join-Path $scratch 'archives'
$keyPath = Join-Path $scratch 'backup.key'
$sourceEnv = Join-Path $scratch 'source.env'
$targetEnv = Join-Path $scratch 'target.env'
$startedSource = $false
$startedTarget = $false
$builtImage = $null
$phase = 'preflight'
$dbPassword = $null
$adminPassword = $null
$customerPassword = $null
$customerToken = $null
$admin = $null

if ($scratch.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create backup smoke files inside the repository.'
}
$null = New-Item -ItemType Directory -Path $scratch -Force
$null = New-Item -ItemType Directory -Path $archiveRoot -Force
$null = New-Item -ItemType Directory -Path $evidenceRoot -Force

function Get-FreeLoopbackPort([System.Collections.Generic.HashSet[int]]$Used) {
    while ($true) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try { $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
        finally { $listener.Stop() }
        if ($Used.Add($port)) { return $port }
    }
}

function Write-PrivateEnvironment([string]$Path, [System.Collections.IDictionary]$Values) {
    $lines = foreach ($name in ($Values.Keys | Sort-Object)) { "$name=$($Values[$name])" }
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($false))
}

function Invoke-FleetFlowCompose([string]$Environment, [string]$Project, [string[]]$Arguments) {
    $composeFile = Join-Path $root 'compose.yaml'
    $output = & docker compose --env-file $Environment --project-name $Project `
        --file $composeFile @Arguments 2>&1
    $status = $LASTEXITCODE
    if ($status -ne 0) { throw "Disposable Compose operation '$($Arguments[0])' failed (exit $status)." }
    return $output
}

function Set-PrivateValue([System.Collections.IDictionary]$Values, [string]$Name, [string]$Value) {
    $Values[$Name] = $Value
}

function Invoke-ExpectedFailureCheck([string]$Environment, [string]$Key) {
    $destination = Join-Path $scratch 'expected-failure-must-not-exist'
    $child = Get-Command pwsh -ErrorAction Stop
    & $child.Source -NoLogo -NoProfile -File (Join-Path $PSScriptRoot 'Backup-FleetFlow.ps1') `
        -Project "fleetflow-absent-$suffix" -EnvironmentFile $Environment `
        -Destination $destination -KeyFile $Key *> $null
    if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath $destination)) {
        throw 'Backup preflight failure did not return nonzero without creating an archive.'
    }
}

function Invoke-AdminRequest([string]$Uri, [hashtable]$Headers, [string]$Method = 'Get', $Body = $null) {
    $parameters = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 10; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    return Invoke-WebRequest @parameters
}

try {
    $phase = 'start isolated source stack'
    $usedPorts = [System.Collections.Generic.HashSet[int]]::new()
    $ports = @{}
    foreach ($name in @('DB_PORT', 'OIDC_PORT', 'REDIS_PORT', 'KAFKA_PORT', 'APP_PORT')) {
        $ports[$name] = Get-FreeLoopbackPort $usedPorts
    }
    $dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    $adminPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    $issuer = "http://127.0.0.1:$($ports.OIDC_PORT)/realms/fleetflow"
    $values = [ordered]@{
        DB_NAME = 'fleetflow'
        DB_USER = 'fleetflow'
        DB_PASSWORD = $dbPassword
        DB_PORT = [string]$ports.DB_PORT
        APP_PORT = [string]$ports.APP_PORT
        OIDC_PORT = [string]$ports.OIDC_PORT
        OIDC_ISSUER = $issuer
        OIDC_AUDIENCE = 'fleetflow-api'
        KC_ADMIN_USER = "day2-$suffix"
        KC_ADMIN_PASSWORD = $adminPassword
        REDIS_PORT = [string]$ports.REDIS_PORT
        KAFKA_PORT = [string]$ports.KAFKA_PORT
        FLEETFLOW_IMAGE_TAG = "day2-backup-$suffix"
        DEMO_ENABLED = 'false'
        DEMO_OIDC_ISSUER = $issuer
    }
    Write-PrivateEnvironment $sourceEnv $values

    $targetValues = [ordered]@{} + $values
    Set-PrivateValue $targetValues 'DB_PORT' ([string](Get-FreeLoopbackPort $usedPorts))
    Set-PrivateValue $targetValues 'REDIS_PORT' ([string](Get-FreeLoopbackPort $usedPorts))
    Set-PrivateValue $targetValues 'KAFKA_PORT' ([string](Get-FreeLoopbackPort $usedPorts))
    Set-PrivateValue $targetValues 'APP_PORT' ([string](Get-FreeLoopbackPort $usedPorts))
    # The restored identity retains the source issuer/host port; all other ports are isolated.
    Write-PrivateEnvironment $targetEnv $targetValues

    $keyBytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    [IO.File]::WriteAllBytes($keyPath, $keyBytes)
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)

    foreach ($project in @($sourceProject, $targetProject)) {
        $envFile = if ($project -eq $sourceProject) { $sourceEnv } else { $targetEnv }
        $existingContainers = @(& docker compose --env-file $envFile --project-name $project `
            --file (Join-Path $root 'compose.yaml') ps -a -q | Where-Object { $_ })
        if ($LASTEXITCODE -ne 0 -or $existingContainers.Count -gt 0) {
            throw 'Generated disposable Compose project unexpectedly already exists.'
        }
        $existingVolumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$project" |
            Where-Object { $_ })
        if ($LASTEXITCODE -ne 0 -or $existingVolumes.Count -gt 0) {
            throw 'Generated disposable Docker volume inventory unexpectedly already exists.'
        }
    }

    $startedSource = $true
    $null = Invoke-FleetFlowCompose $sourceEnv $sourceProject @('up', '--detach', '--build', '--wait', '--wait-timeout', '900')
    $builtImage = "fleetflow-app:$($values.FLEETFLOW_IMAGE_TAG)"

    $identityBase = "http://127.0.0.1:$($ports.OIDC_PORT)"
    $discoveryReady = $false
    $phase = 'wait for isolated identity provider'
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $discovery = Invoke-RestMethod -Uri "$issuer/.well-known/openid-configuration" -TimeoutSec 3
            if ($discovery.issuer -eq $issuer) { $discoveryReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $discoveryReady) { throw 'Disposable Keycloak issuer did not become ready.' }

    $admin = Invoke-RestMethod -Method Post -Uri "$identityBase/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 10 -Body @{
            grant_type = 'password'; client_id = 'admin-cli'; username = $values.KC_ADMIN_USER; password = $adminPassword
        }
    $headers = @{ Authorization = "Bearer $($admin.access_token)" }
    $phase = 'prepare isolated backup fixture'
    $clientResponse = Invoke-AdminRequest "$identityBase/admin/realms/fleetflow/clients?clientId=fleetflow-cli" $headers
    if ($clientResponse.StatusCode -ne 200) { throw 'Could not inspect the disposable signed-verification client.' }
    $clients = @($clientResponse.Content | ConvertFrom-Json)
    if ($clients.Count -ne 1 -or -not $clients[0].directAccessGrantsEnabled -or -not $clients[0].publicClient) {
        throw 'The imported test realm does not have its expected public direct-grant verification client.'
    }

    $customerPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
    $userName = "restore-$suffix"
    $userCreate = Invoke-AdminRequest "$identityBase/admin/realms/fleetflow/users" $headers 'Post' @{
        username = $userName
        firstName = 'Restore'
        lastName = 'Verification'
        enabled = $true
        email = "restore-$suffix@example.invalid"
        emailVerified = $true
        requiredActions = @()
        credentials = @(@{ type = 'password'; value = $customerPassword; temporary = $false })
    }
    if ($userCreate.StatusCode -ne 201) { throw 'Could not create the disposable restore-verification identity.' }
    $createdUsers = @(Invoke-RestMethod -Uri "$identityBase/admin/realms/fleetflow/users?username=$userName&exact=true" `
        -Headers $headers -TimeoutSec 10)
    if ($createdUsers.Count -ne 1 -or $createdUsers[0].email -ne "$userName@example.invalid") {
        throw 'The disposable restore-verification identity could not be confirmed.'
    }
    $subject = [string]$createdUsers[0].id
    $phase = 'confirm disposable identity is eligible for signed login'
    $identityUpdate = Invoke-AdminRequest "$identityBase/admin/realms/fleetflow/users/$subject" $headers 'Put' @{
        username = $userName
        firstName = 'Restore'
        lastName = 'Verification'
        email = "$userName@example.invalid"
        enabled = $true
        emailVerified = $true
        requiredActions = @()
    }
    if ($identityUpdate.StatusCode -ne 204) { throw 'Could not clear pending actions on the disposable restore-verification identity.' }
    $customerTokenResponse = Invoke-WebRequest -Method Post -Uri "$identityBase/realms/fleetflow/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 10 -SkipHttpErrorCheck -Body @{
            grant_type = 'password'; client_id = 'fleetflow-cli'; username = $userName; password = $customerPassword
        }
    if ($customerTokenResponse.StatusCode -ne 200) {
        $loginError = $customerTokenResponse.Content | ConvertFrom-Json
        throw "Disposable identity password grant failed: $($loginError.error) / $($loginError.error_description)."
    }
    $customerToken = $customerTokenResponse.Content | ConvertFrom-Json
    if (-not $customerToken.access_token) { throw 'The disposable identity did not produce a signed verification token before backup.' }
    $customerToken = $null
    $actorId = [guid]::NewGuid().ToString()
    $customerId = [guid]::NewGuid().ToString()
    $merchantId = [guid]::NewGuid().ToString()
    $branchId = [guid]::NewGuid().ToString()
    $orderId = [guid]::NewGuid().ToString()
    $sql = @"
insert into actors(id, issuer, subject, active, created_at, updated_at)
values ('$actorId', '$issuer', '$subject', true, now(), now());
insert into actor_roles(actor_id, role) values ('$actorId', 'CUSTOMER');
insert into customers(id, actor_id, display_name, created_at, updated_at)
values ('$customerId', '$actorId', 'Disposable restore customer', now(), now());
insert into merchants(id, name, created_at, updated_at)
values ('$merchantId', 'Disposable restore merchant', now(), now());
insert into merchant_branches(id, merchant_id, name, pickup_line1, pickup_city, pickup_latitude,
 pickup_longitude, created_at, updated_at)
values ('$branchId', '$merchantId', 'Restore branch', '1 Test Street', 'Cairo', 30, 31, now(), now());
insert into orders(id, customer_id, merchant_branch_id, customer_name_snapshot, merchant_name_snapshot,
 branch_name_snapshot, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, delivery_line1,
 delivery_city, delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at)
values ('$orderId', '$customerId', '$branchId', 'Restore customer', 'Restore merchant', 'Restore branch',
 '1 Test Street', 'Cairo', 30, 31, '2 Test Street', 'Cairo', 30.1, 31.1, 'EGP', 100.00, now(), now());
"@
    $sqlOutput = $sql | & docker compose --env-file $sourceEnv --project-name $sourceProject `
        --file (Join-Path $root 'compose.yaml') exec -T postgres psql -v ON_ERROR_STOP=1 `
        -U $values.DB_USER -d $values.DB_NAME 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated signed restore fixture.' }

    $phase = 'create and verify encrypted backup'
    $archiveOutput = & (Get-Command pwsh -ErrorAction Stop).Source -NoLogo -NoProfile `
        -File (Join-Path $PSScriptRoot 'Backup-FleetFlow.ps1') -Project $sourceProject `
        -EnvironmentFile $sourceEnv -Destination $archiveRoot -KeyFile $keyPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Existing backup implementation failed on the disposable source project.' }
    $archiveText = $archiveOutput -join "`n"
    $archiveMatch = [regex]::Match($archiveText, 'Backup: (?<path>.+\.ffbackup)')
    $hashMatch = [regex]::Match($archiveText, 'SHA256: (?<hash>[0-9A-F]{64})')
    if (-not $archiveMatch.Success -or -not $hashMatch.Success) { throw 'Backup output did not identify its archive and SHA-256.' }
    $archive = $archiveMatch.Groups['path'].Value.Trim()
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'Encrypted backup archive is missing.' }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $hashMatch.Groups['hash'].Value) {
        throw 'Encrypted backup hash differs from its emitted integrity value.'
    }
    $phase = 'verify backup failure exit behavior'
    Invoke-ExpectedFailureCheck $sourceEnv $keyPath

    $phase = 'stop source and restore onto fresh isolated target'
    $null = Invoke-FleetFlowCompose $sourceEnv $sourceProject @('stop')
    $startedTarget = $true
    $restoreOutput = & (Get-Command pwsh -ErrorAction Stop).Source -NoLogo -NoProfile `
        -File (Join-Path $PSScriptRoot 'Restore-FleetFlow.ps1') -Archive $archive -KeyFile $keyPath `
        -TargetProject $targetProject -EnvironmentFile $targetEnv 2>&1
    if ($LASTEXITCODE -ne 0) {
        $restoreDiagnostic = ($restoreOutput | ForEach-Object { [string]$_ }) -join "`n"
        throw "Existing restore implementation failed on the disposable target project. Child diagnostic: $restoreDiagnostic"
    }
    $restoreText = $restoreOutput -join "`n"
    $phase = 'verify restored identity, application and signed workflow'
    if ($restoreText -notmatch 'Signed functional verification UTC:' -or
            $restoreText -notmatch 'Measured RTO seconds:') {
        throw 'Restore did not report application readiness and signed functional verification.'
    }
    $phase = 'verify restored PostGIS'
    $postgis = Invoke-FleetFlowCompose $targetEnv $targetProject @(
        'exec', '-T', 'postgres', 'psql', '-At', '-v', 'ON_ERROR_STOP=1', '-U', $values.DB_USER,
        '-d', $values.DB_NAME, '-c', 'select postgis_full_version()')
    if (($postgis -join "`n") -notmatch 'POSTGIS') { throw 'Restored target does not expose PostGIS.' }

    $phase = 'record source metadata'
    $gitCommit = (& git -C $root rev-parse HEAD).Trim()
    $sourceStatus = ((& git -C $root status --porcelain) -join "`n").Trim()
    $backupMetadata = [ordered]@{
        status = 'PASS_LOCAL_DISPOSABLE_RESTORE_PREPARATION'
        sourceCommit = $gitCommit
        sourceWorkingTreeDirty = [bool]$sourceStatus
        completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        backupArchiveSha256 = $hashMatch.Groups['hash'].Value
        archiveBytes = (Get-Item -LiteralPath $archive).Length
        databasesBackedUp = @('FleetFlow PostgreSQL/PostGIS application database', 'Keycloak local identity data volume')
        archiveAuthenticatedDecryptionAndChecksum = 'PASS; verified by Backup-FleetFlow.ps1 AES-256-GCM round-trip and SHA-256 check'
        restore = 'PASS; empty uniquely named target, restored exact database and identity image IDs'
        postgis = 'PASS; postgis_full_version() returned PostGIS'
        signedFunctionalCheck = 'PASS; restored Keycloak user obtained a token; app read and cancelled a restored scoped order; SQL confirmed CANCELLED'
        negativeBackupPreflight = 'PASS; missing source project returned nonzero and created no archive'
        sourceProject = $sourceProject
        targetProject = $targetProject
        scope = 'Disposable local preparation only; no development project or Azure target was touched'
        rtoSeconds = ([regex]::Match($restoreText, 'Measured RTO seconds: (?<seconds>[0-9.]+)').Groups['seconds'].Value)
    }
    $metadataPath = Join-Path $evidenceRoot 'day2-disposable-backup-restore.json'
    [IO.File]::WriteAllText($metadataPath,
        ($backupMetadata | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "PASS: backup archive integrity and signed disposable restore; metadata: $metadataPath"
}
catch {
    $failureMessage = [string]$_.Exception.Message
    $privateValues = @($dbPassword, $adminPassword, $customerPassword)
    if ($null -ne $admin -and $admin.access_token) { $privateValues += [string]$admin.access_token }
    if ($null -ne $customerToken -and $customerToken.access_token) { $privateValues += [string]$customerToken.access_token }
    foreach ($privateValue in $privateValues) {
        if (-not [string]::IsNullOrEmpty([string]$privateValue)) {
            $failureMessage = $failureMessage.Replace([string]$privateValue, '[REDACTED]')
        }
    }
    $failurePath = Join-Path $evidenceRoot 'day2-disposable-backup-restore-failure.json'
    $failureEvidence = [ordered]@{
        status = 'FAIL_LOCAL_DISPOSABLE_RESTORE_PREPARATION'
        phase = $phase
        errorType = $_.Exception.GetType().FullName
        error = $failureMessage
        completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        sourceProject = $sourceProject
        targetProject = $targetProject
        cleanup = 'Exact generated Compose projects, volumes, image tag, and private scratch directory are removed in finally.'
    }
    [IO.File]::WriteAllText($failurePath,
        ($failureEvidence | ConvertTo-Json -Depth 4) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Error "BLOCKED: disposable backup/restore smoke failed during '$phase' ($($_.Exception.GetType().Name)); safe diagnostic: $failurePath"
    exit 1
}
finally {
    if ($startedTarget -and $targetProject -match '^fleetflow-day2-restore-[0-9a-f]{10}$') {
        try { $null = Invoke-FleetFlowCompose $targetEnv $targetProject @('down', '--volumes', '--remove-orphans') }
        catch { Write-Warning 'Could not fully remove the isolated restore Compose project.' }
    }
    if ($startedSource -and $sourceProject -match '^fleetflow-day2-backup-[0-9a-f]{10}$') {
        try { $null = Invoke-FleetFlowCompose $sourceEnv $sourceProject @('down', '--volumes', '--remove-orphans') }
        catch { Write-Warning 'Could not fully remove the isolated backup source Compose project.' }
    }
    if ($builtImage -and $builtImage -match '^fleetflow-app:day2-backup-[0-9a-f]{10}$') {
        & docker image rm $builtImage *> $null
    }
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    if ($resolvedScratch.StartsWith($scratchRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedScratch) -eq "fleetflow-day2-backup-$suffix") {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force -ErrorAction SilentlyContinue
    }
}
