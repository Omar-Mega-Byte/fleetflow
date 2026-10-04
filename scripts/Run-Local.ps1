$ErrorActionPreference = 'Stop'

$envPath = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Create .env from .env.example and set a local DB_PASSWORD first.'
}

$values = @{}
$previous = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^\s*(#|$)') { continue }
    if ($line -notmatch '^(DB_NAME|DB_USER|DB_PASSWORD|DB_PORT|DB_URL|OIDC_PORT|OIDC_ISSUER|OIDC_JWKS_URI|OIDC_AUDIENCE|KC_ADMIN_USER|KC_ADMIN_PASSWORD)=(.*)$') {
        throw 'Unsupported .env line. Use the keys shown in .env.example.'
    }
    $values[$Matches[1]] = $Matches[2]
}
foreach ($key in @('DB_USER', 'DB_PASSWORD', 'DB_URL', 'OIDC_ISSUER', 'OIDC_JWKS_URI', 'OIDC_AUDIENCE')) {
    if ([string]::IsNullOrWhiteSpace($values[$key]) -or $values[$key] -like 'replace-*') {
        throw "Set $key in .env before starting FleetFlow."
    }
}
foreach ($key in @('DB_USER', 'DB_PASSWORD', 'DB_URL', 'OIDC_ISSUER', 'OIDC_JWKS_URI', 'OIDC_AUDIENCE')) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
}
$previous['SPRING_PROFILES_ACTIVE'] = $env:SPRING_PROFILES_ACTIVE
$env:SPRING_PROFILES_ACTIVE = 'local'

Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd spring-boot:run --no-transfer-progress
    if ($LASTEXITCODE -ne 0) { throw "FleetFlow exited with code $LASTEXITCODE." }
}
finally {
    Pop-Location
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
