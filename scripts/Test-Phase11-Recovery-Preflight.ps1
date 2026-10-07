$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $tempRoot ('fleetflow-recovery-check-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $scratch
try {
    $keyFile = Join-Path $scratch 'key.bin'
    $key = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($key)
    [IO.File]::WriteAllBytes($keyFile, $key)
    [Array]::Clear($key, 0, $key.Length)
    $inputFile = Join-Path $scratch 'payload.bin'
    [IO.File]::WriteAllBytes($inputFile, [Text.Encoding]::UTF8.GetBytes('phase11-recovery-preflight'))
    $archive = Join-Path $scratch 'archive.ffbackup'
    $outputFile = Join-Path $scratch 'output.bin'
    Import-Module (Join-Path $PSScriptRoot 'BackupCrypto.psm1') -Force
    Protect-FleetFlowArchive -InputFile $inputFile -OutputFile $archive -KeyFile $keyFile
    Unprotect-FleetFlowArchive -InputFile $archive -OutputFile $outputFile -KeyFile $keyFile
    if ((Get-FileHash $inputFile -Algorithm SHA256).Hash -ne
            (Get-FileHash $outputFile -Algorithm SHA256).Hash) {
        throw 'Backup encryption round trip changed the payload.'
    }
    $bytes = [IO.File]::ReadAllBytes($archive)
    $bytes[$bytes.Length - 1] = $bytes[$bytes.Length - 1] -bxor 1
    [IO.File]::WriteAllBytes($archive, $bytes)
    $rejected = $false
    try { Unprotect-FleetFlowArchive -InputFile $archive -OutputFile $outputFile -KeyFile $keyFile }
    catch [Security.Cryptography.AuthenticationTagMismatchException] { $rejected = $true }
    if (-not $rejected) { throw 'Tampered backup was not rejected.' }
    Write-Output 'Recovery preflight: AES-GCM round trip and tamper rejection passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($scratch)
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolved).StartsWith('fleetflow-recovery-check-')) {
        throw 'Recovery preflight cleanup target failed validation.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
