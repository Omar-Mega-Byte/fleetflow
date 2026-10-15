$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$secretDir = Join-Path $tempRoot ('fleetflow-compose-check-' + [guid]::NewGuid().ToString('N'))
$previousSecretDir = $env:FLEETFLOW_SECRET_DIR
$null = New-Item -ItemType Directory -Path $secretDir
try {
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
}
finally {
    if ($null -eq $previousSecretDir) { Remove-Item Env:\FLEETFLOW_SECRET_DIR -ErrorAction SilentlyContinue }
    else { $env:FLEETFLOW_SECRET_DIR = $previousSecretDir }
    $resolved = [IO.Path]::GetFullPath($secretDir)
    if (-not [string]::Equals((Split-Path -Parent $resolved), $tempRoot.TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolved).StartsWith('fleetflow-compose-check-')) {
        throw 'Temporary secret directory failed cleanup validation.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
