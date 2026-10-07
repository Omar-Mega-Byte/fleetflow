$ErrorActionPreference = 'Stop'

$envPath = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Create .env from .env.example and set a local DB_PASSWORD first.'
}

$values = @{}
$previous = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^\s*(#|$)') { continue }
    if ($line -notmatch '^(DB_NAME|DB_USER|DB_PASSWORD|DB_PORT|DB_URL|APP_PORT|OIDC_PORT|OIDC_ISSUER|OIDC_JWKS_URI|OIDC_AUDIENCE|KC_ADMIN_USER|KC_ADMIN_PASSWORD|REDIS_HOST|REDIS_PORT|KAFKA_BOOTSTRAP_SERVERS|KAFKA_PORT|OUTBOX_ENABLED|OUTBOX_BATCH_SIZE|OUTBOX_SWEEP_INTERVAL_MS|OUTBOX_LEASE_DURATION|OUTBOX_SEND_TIMEOUT|OUTBOX_MAX_ATTEMPTS|OUTBOX_PUBLISHED_RETENTION|OUTBOX_PRUNE_BATCH_SIZE|OUTBOX_PRUNE_INTERVAL_MS|RECOVERY_ENABLED|RECOVERY_BATCH_SIZE|RECOVERY_SWEEP_INTERVAL_MS|RATE_READ_LIMIT|RATE_EXPENSIVE_READ_LIMIT|RATE_COMMAND_LIMIT|RATE_STREAM_LIMIT|GRAFANA_ADMIN_USER|GRAFANA_SECRET_FILE|GRAFANA_PORT)=(.*)$') {
        throw 'Unsupported .env line. Use the keys shown in .env.example.'
    }
    $values[$Matches[1]] = $Matches[2]
}
$values['REDIS_HOST'] = if ($values['REDIS_HOST']) { $values['REDIS_HOST'] } else { '127.0.0.1' }
$values['REDIS_PORT'] = if ($values['REDIS_PORT']) { $values['REDIS_PORT'] } else { '6379' }
$values['KAFKA_BOOTSTRAP_SERVERS'] = if ($values['KAFKA_BOOTSTRAP_SERVERS']) {
    $values['KAFKA_BOOTSTRAP_SERVERS']
} else { '127.0.0.1:9094' }
foreach ($key in @('DB_USER', 'DB_PASSWORD', 'DB_URL', 'OIDC_ISSUER', 'OIDC_JWKS_URI', 'OIDC_AUDIENCE', 'REDIS_HOST', 'REDIS_PORT', 'KAFKA_BOOTSTRAP_SERVERS')) {
    if ([string]::IsNullOrWhiteSpace($values[$key]) -or $values[$key] -like 'replace-*') {
        throw "Set $key in .env before starting FleetFlow."
    }
}
foreach ($key in @('DB_USER', 'DB_PASSWORD', 'DB_URL', 'OIDC_ISSUER', 'OIDC_JWKS_URI', 'OIDC_AUDIENCE', 'REDIS_HOST', 'REDIS_PORT', 'KAFKA_BOOTSTRAP_SERVERS', 'OUTBOX_ENABLED', 'OUTBOX_BATCH_SIZE', 'OUTBOX_SWEEP_INTERVAL_MS', 'OUTBOX_LEASE_DURATION', 'OUTBOX_SEND_TIMEOUT', 'OUTBOX_MAX_ATTEMPTS', 'OUTBOX_PUBLISHED_RETENTION', 'OUTBOX_PRUNE_BATCH_SIZE', 'OUTBOX_PRUNE_INTERVAL_MS', 'RECOVERY_ENABLED', 'RECOVERY_BATCH_SIZE', 'RECOVERY_SWEEP_INTERVAL_MS', 'RATE_READ_LIMIT', 'RATE_EXPENSIVE_READ_LIMIT', 'RATE_COMMAND_LIMIT', 'RATE_STREAM_LIMIT')) {
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
