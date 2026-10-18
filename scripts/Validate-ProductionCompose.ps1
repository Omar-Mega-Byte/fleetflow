$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$secretDir = Join-Path $tempRoot ('fleetflow-compose-check-' + [guid]::NewGuid().ToString('N'))
$previousSecretDir = $env:FLEETFLOW_SECRET_DIR
$subjectKeys = @('DEMO_CUSTOMER_SUBJECT', 'DEMO_MERCHANT_SUBJECT', 'DEMO_DRIVER_SUBJECT',
    'DEMO_DISPATCHER_SUBJECT', 'DEMO_OPERATOR_SUBJECT')
$previousSubjects = @{}
$null = New-Item -ItemType Directory -Path $secretDir
try {
    foreach ($key in $subjectKeys) {
        $previousSubjects[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, [guid]::NewGuid().ToString())
    }
    foreach ($name in @('postgres_db_password', 'app_db_password', 'keycloak_db_password',
            'keycloak_admin_password', 'tls_fullchain.pem', 'tls_private_key.pem')) {
        [IO.File]::WriteAllText((Join-Path $secretDir $name), 'ci-placeholder-only')
    }
    $env:FLEETFLOW_SECRET_DIR = $secretDir
    $compose = @('--env-file', (Join-Path $root '.env.production.example'), '--profile', 'public',
        '-f', (Join-Path $root 'ops/production/compose.production.yaml'), 'config', '--format', 'json')
    $document = & docker compose @compose
    if ($LASTEXITCODE -ne 0) { throw 'Production Compose rendering failed.' }
    $document | & python (Join-Path $root 'scripts/Validate-ProductionCompose.py')
    if ($LASTEXITCODE -ne 0) { throw 'Production Compose boundary validation failed.' }
    $rendered = ($document -join "`n") | ConvertFrom-Json -AsHashtable
    $originalSubject = $rendered.services.app.environment.DEMO_CUSTOMER_SUBJECT
    foreach ($badSubject in @('11111111-1111-4111-8111-111111111111',
            $rendered.services.app.environment.DEMO_MERCHANT_SUBJECT,
            '00000000-0000-0000-0000-000000000000')) {
        $rendered.services.app.environment.DEMO_CUSTOMER_SUBJECT = $badSubject
        $null = ($rendered | ConvertTo-Json -Depth 100) |
            & python (Join-Path $root 'scripts/Validate-ProductionCompose.py') 2>&1
        if ($LASTEXITCODE -eq 0) { throw 'Production Compose accepted an example, duplicate or nil identity subject.' }
    }
    $rendered.services.app.environment.DEMO_CUSTOMER_SUBJECT = $originalSubject
}
finally {
    foreach ($key in $subjectKeys) {
        [Environment]::SetEnvironmentVariable($key, $previousSubjects[$key])
    }
    if ($null -eq $previousSecretDir) { Remove-Item Env:\FLEETFLOW_SECRET_DIR -ErrorAction SilentlyContinue }
    else { $env:FLEETFLOW_SECRET_DIR = $previousSecretDir }
    $resolved = [IO.Path]::GetFullPath($secretDir)
    if (-not [string]::Equals((Split-Path -Parent $resolved), $tempRoot.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolved).StartsWith('fleetflow-compose-check-')) {
        throw 'Temporary secret directory failed cleanup validation.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
