param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$Destination,
    [Parameter(Mandatory)][string]$KeyFile,
    [string]$CopyDestination,
    [string]$RemoteDestination,
    [ValidateRange(1, 365)][int]$RetentionDays = 7
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
if ($Project -cnotmatch '^[a-z][a-z0-9-]{1,40}$') { throw 'Invalid Compose project name.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = (Resolve-Path -LiteralPath $EnvironmentFile).Path
$keyPath = (Resolve-Path -LiteralPath $KeyFile).Path
$copyPath = $null
$destPath = [IO.Path]::GetFullPath($Destination)
if ($destPath.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $destPath.Equals($root, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup destination must be outside the repository.'
}
if ($keyPath.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup key must be outside the repository.'
}
if ($CopyDestination) {
    $copyPath = [IO.Path]::GetFullPath($CopyDestination)
    if ($copyPath.Equals($destPath, [StringComparison]::OrdinalIgnoreCase) -or
            $copyPath.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Copy destination must differ from the primary destination and be outside the repository.'
    }
}
if ($RemoteDestination) {
    if ($RemoteDestination -cnotmatch '^[A-Za-z0-9_-]+:[A-Za-z0-9_./-]+$' -or
            $RemoteDestination.Contains('..')) {
        throw 'Remote destination must be a named rclone remote and a dedicated folder.'
    }
    if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
        throw 'rclone is required for the remote backup copy.'
    }
}
Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'BackupRetention.psm1') -Force
if ([IO.File]::ReadAllBytes($keyPath).Length -ne 32) { throw 'Backup key must contain 32 bytes.' }
$null = Get-Command docker -ErrorAction Stop
$composeArgs = @('compose', '--env-file', $envPath, '-p', $Project, '-f', (Join-Path $root 'compose.yaml'))
function Invoke-Compose([string[]]$Arguments) {
    & docker @composeArgs @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose $($Arguments[0]) failed." }
}
function Require-Running([string]$Service) {
    $idOutput = & docker @composeArgs ps -q $Service
    if ($LASTEXITCODE -ne 0) { throw "Compose status failed for $Service." }
    if (-not $idOutput) { throw "$Service container is missing." }
    $id = ([string]$idOutput).Trim()
    $runningOutput = & docker inspect --format '{{.State.Running}}' $id
    if ($LASTEXITCODE -ne 0) { throw "Container inspection failed for $Service." }
    if (-not $runningOutput) { throw "Container inspection returned no state for $Service." }
    $running = ([string]$runningOutput).Trim()
    if ($running -ne 'true') { throw "$Service must be running." }
    return $id
}

$pg = Require-Running 'postgres'
$null = Require-Running 'app'
$keycloak = Require-Running 'keycloak'
$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $tempRoot ("fleetflow-backup-" + [guid]::NewGuid().ToString('N'))
$payload = Join-Path $scratch 'payload'
$null = New-Item -ItemType Directory -Path $payload -Force
$appStopped = $false
$identityStopped = $false
try {
    # The one-app local demo is quiesced so database actors and Keycloak subjects align.
    Invoke-Compose -Arguments @('stop', 'app')
    $appStopped = $true
    Invoke-Compose -Arguments @('stop', 'keycloak')
    $identityStopped = $true

    & docker exec $pg sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/fleetflow-phase11.dump'
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
    try {
        & docker exec $pg pg_restore -l /tmp/fleetflow-phase11.dump | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'pg_restore could not list the dump.' }
        & docker cp "${pg}:/tmp/fleetflow-phase11.dump" (Join-Path $payload 'postgres.dump') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Copying the database dump failed.' }
    } finally {
        & docker exec $pg rm -f /tmp/fleetflow-phase11.dump | Out-Null
    }

    $helperImage = 'redis:7.4.7-alpine@sha256:02f2cc4882f8bf87c79a220ac958f58c700bdec0dfb9b9ea61b62fb0e8f1bfcf'
    & docker run --pull never --rm --volumes-from $keycloak -v "${payload}:/backup" `
        --entrypoint sh $helperImage -c 'tar -C /opt/keycloak/data --exclude ./import/fleetflow-realm.json -cf /backup/keycloak.tar .'
    if ($LASTEXITCODE -ne 0) { throw 'Keycloak volume snapshot failed.' }

    $dbImage = (& docker inspect --format '{{.Image}}' $pg).Trim()
    $identityImage = (& docker inspect --format '{{.Image}}' $keycloak).Trim()
    $dumpVersion = (& docker exec $pg pg_dump --version).Trim()
    $dbName = (& docker exec $pg sh -c 'printf %s "$POSTGRES_DB"').Trim()
    $manifest = [ordered]@{
        format = 'FleetFlow local backup v1'
        project = $Project
        createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        consistency = 'Single app and Keycloak stopped before PostgreSQL dump and identity snapshot'
        databaseImageId = $dbImage
        databaseName = $dbName
        keycloakImageId = $identityImage
        pgDumpVersion = $dumpVersion
        postgresSha256 = (Get-FileHash -LiteralPath (Join-Path $payload 'postgres.dump') -Algorithm SHA256).Hash
        keycloakSha256 = (Get-FileHash -LiteralPath (Join-Path $payload 'keycloak.tar') -Algorithm SHA256).Hash
        kafka = 'Not included; replay only retained unpublished outbox events'
        redis = 'Not included; location and heartbeat state must be refreshed'
    }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $payload 'manifest.json') -Encoding utf8
    $zip = Join-Path $scratch 'payload.zip'
    Compress-Archive -Path (Join-Path $payload '*') -DestinationPath $zip -CompressionLevel Optimal
    $null = New-Item -ItemType Directory -Path $destPath -Force
    $backup = Join-Path $destPath "fleetflow-$Project-$timestamp.ffbackup"
    Protect-FleetFlowArchive -InputFile $zip -OutputFile $backup -KeyFile $keyPath
    $verifiedZip = Join-Path $scratch 'verified.zip'
    Unprotect-FleetFlowArchive -InputFile $backup -OutputFile $verifiedZip -KeyFile $keyPath
    if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $verifiedZip -Algorithm SHA256).Hash) {
        throw 'Encrypted backup round-trip checksum mismatch.'
    }
    if ($CopyDestination) {
        $null = New-Item -ItemType Directory -Path $copyPath -Force
        $copy = Join-Path $copyPath (Split-Path -Leaf $backup)
        Copy-Item -LiteralPath $backup -Destination $copy -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne
                (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash) {
            throw 'Second-copy checksum mismatch.'
        }
    }
    if ($RemoteDestination) {
        $remoteFile = "$($RemoteDestination.TrimEnd('/'))/$(Split-Path -Leaf $backup)"
        & rclone copyto $backup $remoteFile --no-traverse --retries 2
        if ($LASTEXITCODE -ne 0) { throw 'Remote backup upload failed.' }
        $remoteListing = @(& rclone lsf $RemoteDestination --files-only)
        if ($LASTEXITCODE -ne 0 -or $remoteListing -notcontains (Split-Path -Leaf $backup)) {
            throw 'Remote backup artifact is not independently listable.'
        }
        $retrieved = Join-Path $scratch 'remote-retrieved.ffbackup'
        & rclone copyto $remoteFile $retrieved --retries 2
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $retrieved)) {
            throw 'Remote backup artifact could not be retrieved.'
        }
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne
                (Get-FileHash -LiteralPath $retrieved -Algorithm SHA256).Hash) {
            throw 'Retrieved remote backup checksum mismatch.'
        }
        # Retain only this project's timestamped archives, after the new copy is verified.
        $remoteListing = @(& rclone lsf $RemoteDestination --files-only)
        if ($LASTEXITCODE -ne 0) { throw 'Remote retention listing failed.' }
        $cutoff = [DateTime]::UtcNow.AddDays(-$RetentionDays)
        $expired = @(Get-FleetFlowExpiredRemoteArchives -Names $remoteListing -Project $Project -CutoffUtc $cutoff)
        foreach ($name in $expired) {
            & rclone deletefile "$($RemoteDestination.TrimEnd('/'))/$name"
            if ($LASTEXITCODE -ne 0) { throw 'Remote retention deletion failed.' }
        }
    }
    foreach ($folder in @($destPath, $copyPath)) {
        if (-not $folder) { continue }
        Get-ChildItem -LiteralPath $folder -File -Filter "fleetflow-$Project-*.ffbackup" |
            Where-Object { $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-$RetentionDays) } |
            ForEach-Object {
                $resolved = [IO.Path]::GetFullPath($_.FullName)
                if (-not $resolved.StartsWith($folder + [IO.Path]::DirectorySeparatorChar,
                        [StringComparison]::OrdinalIgnoreCase)) { throw 'Retention target escaped its directory.' }
                Remove-Item -LiteralPath $resolved -Force
            }
    }
    Write-Output "Backup: $backup"
    Write-Output "Created UTC: $($manifest.createdAtUtc)"
    Write-Output "SHA256: $((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash)"
    Write-Output "Second copy verified: $([bool]$CopyDestination)"
    Write-Output "Remote copy retrieved and verified: $([bool]$RemoteDestination)"
} finally {
    try {
        if ($identityStopped) { Invoke-Compose -Arguments @('start', 'keycloak') }
    } finally {
        try {
            if ($appStopped) { Invoke-Compose -Arguments @('start', 'app') }
        } finally {
            $resolvedScratch = [IO.Path]::GetFullPath($scratch)
            if (-not $resolvedScratch.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
                    -not (Split-Path -Leaf $resolvedScratch).StartsWith('fleetflow-backup-')) {
                throw 'Scratch path failed cleanup validation.'
            }
            Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
        }
    }
}
