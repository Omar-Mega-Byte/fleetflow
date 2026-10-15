param(
    [Parameter(Mandatory)][string]$Archive,
    [Parameter(Mandatory)][string]$KeyFile,
    [Parameter(Mandatory)][string]$TargetProject,
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [switch]$NoStartApp
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
if ($TargetProject -cnotmatch '^[a-z][a-z0-9-]{1,40}$') { throw 'Invalid Compose project name.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$keyPath = (Resolve-Path -LiteralPath $KeyFile).Path
$envPath = (Resolve-Path -LiteralPath $EnvironmentFile).Path
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required for restore.' }
if ([IO.File]::ReadAllBytes($keyPath).Length -ne 32) { throw 'Backup key must contain 32 bytes.' }
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
foreach ($name in @('DB_NAME', 'DB_USER', 'DB_PASSWORD', 'KC_ADMIN_USER', 'KC_ADMIN_PASSWORD', 'OIDC_ISSUER', 'OIDC_AUDIENCE')) {
    if (-not $settings[$name]) { throw "Missing $name in private environment file." }
}
if ($settings['OIDC_ISSUER'] -notmatch '^http://127\.0\.0\.1:\d+/realms/fleetflow$') {
    throw 'The restore rehearsal requires the local loopback OIDC issuer.'
}
Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
$composeArgs = @('compose', '--env-file', $envPath, '-p', $TargetProject,
    '-f', (Join-Path $root 'compose.yaml'))
function Invoke-Compose([string[]]$Arguments) {
    & docker @composeArgs @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose $($Arguments[0]) failed." }
}
function Get-RepoDigestForImageId([string]$ImageId, [string]$Repository) {
    $json = & docker image inspect --format '{{json .RepoDigests}}' $ImageId
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect archived image $Repository ($ImageId)." }
    $digests = @($json | ConvertFrom-Json)
    $match = $digests | Where-Object { $_ -like "$Repository@sha256:*" } | Select-Object -First 1
    if (-not $match) {
        throw "Archived image $Repository ($ImageId) is not available locally by its pinned repository digest."
    }
    return [string]$match
}
$previousPostgisImage = $env:FLEETFLOW_POSTGIS_IMAGE
$previousKeycloakImage = $env:FLEETFLOW_KEYCLOAK_IMAGE

$containers = @(& docker @composeArgs ps -a -q | Where-Object { $_ })
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect target Compose project.' }
$volumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$TargetProject" |
    Where-Object { $_ })
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect target Docker volumes.' }
if ($containers.Count -gt 0 -or $volumes.Count -gt 0) {
    throw 'Target project already has containers or volumes; choose a new project name. No data was changed.'
}

$startedAt = [DateTimeOffset]::UtcNow
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $tempRoot ("fleetflow-restore-" + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $scratch
try {
    $zip = Join-Path $scratch 'payload.zip'
    Unprotect-FleetFlowArchive -InputFile $archivePath -OutputFile $zip -KeyFile $keyPath
    $payload = Join-Path $scratch 'payload'
    Expand-Archive -LiteralPath $zip -DestinationPath $payload
    $manifest = Get-Content -LiteralPath (Join-Path $payload 'manifest.json') -Raw | ConvertFrom-Json -DateKind String
    if ($manifest.format -ne 'FleetFlow local backup v1' -or $manifest.project -eq $TargetProject) {
        throw 'Archive format invalid or target project equals the source project.'
    }
    # Restore with the exact database and identity image digests recorded in the archive.
    # The current Compose defaults can then advance independently of saved-volume recovery.
    $env:FLEETFLOW_POSTGIS_IMAGE = Get-RepoDigestForImageId $manifest.databaseImageId 'postgis/postgis'
    $env:FLEETFLOW_KEYCLOAK_IMAGE = Get-RepoDigestForImageId $manifest.keycloakImageId 'quay.io/keycloak/keycloak'
    foreach ($item in @(@('postgres.dump', $manifest.postgresSha256),
            @('keycloak.tar', $manifest.keycloakSha256))) {
        $actual = (Get-FileHash -LiteralPath (Join-Path $payload $item[0]) -Algorithm SHA256).Hash
        if ($actual -ne $item[1]) { throw "Backup payload integrity failed: $($item[0])." }
    }
    $dbName = $settings['DB_NAME']
    if ($dbName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $dbName -ne $manifest.databaseName) {
        throw 'Target DB_NAME must match the backed-up database name.'
    }

    # The target was proven empty before this point. No existing project volume is touched.
    Invoke-Compose -Arguments @('up', '-d', '--no-build', '--pull', 'never', 'postgres')
    $pg = (& docker @composeArgs ps -q postgres).Trim()
    if (-not $pg) { throw 'Fresh target PostgreSQL container is missing.' }
    if ((& docker inspect --format '{{.Image}}' $pg).Trim() -ne $manifest.databaseImageId) {
        throw 'Target PostgreSQL/PostGIS image ID differs from the backup.'
    }
    # pg_isready can succeed against the temporary initdb server while PostGIS scripts still run.
    $initialized = $false
    for ($i = 0; $i -lt 60; $i++) {
        $state = (& docker inspect --format '{{.State.Status}}' $pg).Trim()
        if ($state -eq 'exited') { throw 'Fresh PostGIS container exited during initialization.' }
        $recentLog = & docker logs $pg 2>&1
        if ($recentLog -match 'PostgreSQL init process complete; ready for start up\.') {
            $initialized = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $initialized) { throw 'Fresh target PostGIS initialization did not complete.' }
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        & docker exec $pg sh -c 'pg_isready -U "$POSTGRES_USER" -d postgres' | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Fresh target PostgreSQL did not become ready after init.' }
    & docker cp (Join-Path $payload 'postgres.dump') "${pg}:/tmp/fleetflow-phase11.dump" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Copying dump into fresh target failed.' }
    try {
        & docker exec $pg sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --exit-on-error --clean --if-exists --create --no-owner --no-acl -U "$POSTGRES_USER" -d postgres /tmp/fleetflow-phase11.dump'
        if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed. The fresh target is retained for inspection.' }
    } finally {
        & docker exec $pg rm -f /tmp/fleetflow-phase11.dump | Out-Null
    }

    Invoke-Compose -Arguments @('create', 'keycloak')
    $identity = (& docker @composeArgs ps -a -q keycloak).Trim()
    if (-not $identity) { throw 'Fresh target Keycloak container is missing.' }
    if ((& docker inspect --format '{{.Image}}' $identity).Trim() -ne $manifest.keycloakImageId) {
        throw 'Target Keycloak image ID differs from the backup.'
    }
    $helperImage = 'redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
    & docker run --pull never --rm --volumes-from $identity -v "${payload}:/backup" `
        --entrypoint sh $helperImage -c 'tar -C /opt/keycloak/data --exclude ./import/fleetflow-realm.json -xf /backup/keycloak.tar'
    if ($LASTEXITCODE -ne 0) { throw 'Restoring the fresh Keycloak volume failed.' }

    if (-not $NoStartApp) {
        Invoke-Compose -Arguments @('up', '-d', '--build', '--pull', 'never',
            'keycloak', 'redis', 'kafka', 'app')
        $app = (& docker @composeArgs ps -q app).Trim()
        if (-not $app) { throw 'Restored app container is missing.' }
        $healthy = $false
        for ($i = 0; $i -lt 60; $i++) {
            & docker exec $app wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness
            if ($LASTEXITCODE -eq 0) { $healthy = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $healthy) { throw 'Restored app did not become ready; target is retained for inspection.' }
        $serviceReadyAt = [DateTimeOffset]::UtcNow
        $issuer = $settings['OIDC_ISSUER'] -replace '/realms/fleetflow$', ''
        $identityReady = $false
        for ($i = 0; $i -lt 120; $i++) {
            try {
                $identityStatus = Invoke-WebRequest -Uri "$issuer/realms/fleetflow/.well-known/openid-configuration" `
                    -TimeoutSec 3 -SkipHttpErrorCheck
                if ($identityStatus.StatusCode -eq 200) { $identityReady = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
        }
        if (-not $identityReady) { throw 'Restored Keycloak realm did not become ready.' }
        & (Join-Path $PSScriptRoot 'Verify-Restored-FleetFlow.ps1') -Project $TargetProject -EnvironmentFile $envPath
        $functionalAt = [DateTimeOffset]::UtcNow
    }
    $finishedAt = [DateTimeOffset]::UtcNow
    Write-Output "Restored project: $TargetProject"
    Write-Output "Backup timestamp UTC: $($manifest.createdAtUtc)"
    Write-Output "Restore started UTC: $($startedAt.ToString('o'))"
    Write-Output "Restore finished UTC: $($finishedAt.ToString('o'))"
    Write-Output "Elapsed seconds: $([math]::Round(($finishedAt - $startedAt).TotalSeconds, 1))"
    if (-not $NoStartApp) {
        Write-Output "Service ready UTC: $($serviceReadyAt.ToString('o'))"
        Write-Output "Signed functional verification UTC: $($functionalAt.ToString('o'))"
        Write-Output "Measured RTO seconds: $([math]::Round(($functionalAt - $startedAt).TotalSeconds, 1))"
    } else {
        Write-Output 'Application and signed functional verification skipped by -NoStartApp.'
    }
} finally {
    if ($null -eq $previousPostgisImage) { Remove-Item Env:FLEETFLOW_POSTGIS_IMAGE -ErrorAction SilentlyContinue }
    else { $env:FLEETFLOW_POSTGIS_IMAGE = $previousPostgisImage }
    if ($null -eq $previousKeycloakImage) { Remove-Item Env:FLEETFLOW_KEYCLOAK_IMAGE -ErrorAction SilentlyContinue }
    else { $env:FLEETFLOW_KEYCLOAK_IMAGE = $previousKeycloakImage }
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    if (-not $resolvedScratch.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolvedScratch).StartsWith('fleetflow-restore-')) {
        throw 'Scratch path failed cleanup validation.'
    }
    Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
}
