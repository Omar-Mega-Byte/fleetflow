param(
    [Parameter(Mandatory = $true)]
    [guid]$Subject,
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$ComposeProject = 'fleetflow'
)

$ErrorActionPreference = 'Stop'
$envPath = $EnvFile
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Create .env from .env.example before bootstrapping the local admin.'
}
if ($ComposeProject -notmatch '^[a-z][a-z0-9_-]*$') {
    throw 'Invalid Compose project name.'
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^(DB_NAME|DB_USER|OIDC_ISSUER)=(.*)$') {
        $values[$Matches[1]] = $Matches[2]
    }
}
if ($values['OIDC_ISSUER'] -notmatch '^http://127\.0\.0\.1:\d+/realms/fleetflow$') {
    throw 'This script only provisions the loopback FleetFlow development realm.'
}
foreach ($key in @('DB_NAME', 'DB_USER')) {
    if ($values[$key] -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid $key in .env."
    }
}

$actorId = [guid]::NewGuid()
$issuer = $values['OIDC_ISSUER']
$sql = @"
BEGIN;
INSERT INTO actors (id, issuer, subject, active, created_at, updated_at)
VALUES ('$actorId', '$issuer', '$Subject', true, now(), now());
INSERT INTO actor_roles (actor_id, role) VALUES ('$actorId', 'ADMIN');
COMMIT;
"@

Push-Location (Join-Path $PSScriptRoot '..')
try {
    $sql | docker compose --env-file $envPath -p $ComposeProject exec -T postgres psql `
        -v ON_ERROR_STOP=1 -U $values['DB_USER'] -d $values['DB_NAME'] | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Local ADMIN bootstrap failed. No role was granted.'
    }
    Write-Host "Provisioned local ADMIN actor $actorId."
    $actorId
}
finally {
    Pop-Location
}
