$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
$source = $PSHOME
$target = Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11\powershell-task-7.6.5'
$log = Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11\task-runtime-install.log'
if (-not (Test-Path -LiteralPath (Join-Path $source 'pwsh.exe') -PathType Leaf)) {
    throw 'Source PowerShell runtime is missing.'
}
if (Test-Path -LiteralPath $target) { throw 'Task runtime target already exists.' }
Start-Transcript -Path $log -Append | Out-Null
try {
    Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $source 'pwsh.exe') -Algorithm SHA256).Hash
    $targetHash = (Get-FileHash -LiteralPath (Join-Path $target 'pwsh.exe') -Algorithm SHA256).Hash
    if ($sourceHash -ne $targetHash) { throw 'Task runtime executable hash mismatch.' }
    & (Join-Path $target 'pwsh.exe') --version
    if ($LASTEXITCODE -ne 0) { throw 'Copied task runtime did not execute.' }
    Write-Output 'Stable task PowerShell runtime installed and verified.'
} finally {
    Stop-Transcript | Out-Null
}
