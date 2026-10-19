param(
    [string]$OutputDirectory = (Join-Path $env:LOCALAPPDATA ('FleetFlow\private-login-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
if ($resolvedOutput.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Credential output must be outside the Git checkout.'
}
if (Test-Path -LiteralPath $resolvedOutput) { throw 'Choose a new output directory.' }
New-Item -ItemType Directory -Path $resolvedOutput | Out-Null
$owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $resolvedOutput /inheritance:r /grant:r "${owner}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict credential directory permissions.' }

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'mvnw.cmd') -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed.' }
    $classpathFile = Join-Path $resolvedOutput 'runtime-classpath.txt'
    & (Join-Path $projectRoot 'mvnw.cmd') -q dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Maven classpath generation failed.' }
    $runtimeClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $classpath = (Join-Path $projectRoot 'target\classes') + ';' + $runtimeClasspath
    $records = @()
    foreach ($persona in @('customer', 'merchant', 'driver', 'dispatcher', 'operator')) {
        $randomBytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
        $password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        $hash = ($password | & java -cp $classpath com.fleetflow.security.LocalPasswordHashTool).Trim()
        if ($LASTEXITCODE -ne 0 -or $hash -notmatch '^\$2[aby]\$12\$') { throw "Hash generation failed for $persona." }
        $records += [pscustomobject]@{
            persona = $persona
            email = "$persona@fleetflow.demo"
            password = $password
            passwordHash = $hash
        }
    }
    $credentialFile = Join-Path $resolvedOutput 'demo-credentials.json'
    $records | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $credentialFile -Encoding utf8
    Write-Output "Created private demo credentials at $credentialFile"
} finally {
    Pop-Location
    if (Test-Path -LiteralPath (Join-Path $resolvedOutput 'runtime-classpath.txt')) {
        Remove-Item -LiteralPath (Join-Path $resolvedOutput 'runtime-classpath.txt')
    }
}
