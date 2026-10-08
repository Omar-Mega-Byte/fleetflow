param([Parameter(Mandatory)][string]$Project, [switch]$DiagnosticOnly, [switch]$InitializeKey)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or later is required.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backupHome = Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11'
$null = New-Item -ItemType Directory -Path $backupHome -Force
$log = Join-Path $backupHome 'scheduled-backup.log'
$scheduledKey = Join-Path $backupHome 'scheduled-backup-key.bin'
$dockerBin = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'
$rcloneBin = Join-Path $env:USERPROFILE 'Tools\rclone'
$env:PATH = "$dockerBin;$rcloneBin;$env:PATH"

$failed = $false
Start-Transcript -Path $log -Append | Out-Null
try {
    if ($DiagnosticOnly) {
        Write-Output "Runtime user: $(whoami)"
        Write-Output "Original key visible: $(Test-Path -LiteralPath (Join-Path $backupHome 'backup-key.bin') -PathType Leaf)"
        Write-Output "Scheduled key visible: $(Test-Path -LiteralPath $scheduledKey -PathType Leaf)"
        Write-Output "Docker visible: $([bool](Get-Command docker -ErrorAction SilentlyContinue))"
        Write-Output "rclone visible: $([bool](Get-Command rclone -ErrorAction SilentlyContinue))"
        return
    }
    if ($InitializeKey) {
        if (Test-Path -LiteralPath $scheduledKey) { throw 'Scheduled backup key already exists.' }
        $bytes = [byte[]]::new(32)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        try { [IO.File]::WriteAllBytes($scheduledKey, $bytes) }
        finally { [Array]::Clear($bytes, 0, $bytes.Length) }
        $identity = (whoami).Trim()
        & icacls.exe $scheduledKey /inheritance:r /grant:r "${identity}:(F)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Scheduled backup key ACL could not be restricted.' }
        Write-Output 'Scheduled backup key initialized in the task context (32 bytes).'
        return
    }
    Write-Output "FleetFlow scheduled backup started UTC: $([DateTimeOffset]::UtcNow.ToString('o'))"
    & (Join-Path $PSScriptRoot 'Invoke-Phase11ScheduledBackup.ps1') -Project $Project `
        -EnvironmentFile (Join-Path $root '.env.phase11-monitoring') `
        -Destination (Join-Path $backupHome 'backups') `
        -KeyFile $scheduledKey `
        -RemoteDestination 'gdrive:FleetFlow-Backups' `
        -DiscordWebhookFile (Join-Path $root '.env.discord-webhook') -RetentionDays 7
    Write-Output "FleetFlow scheduled backup completed UTC: $([DateTimeOffset]::UtcNow.ToString('o'))"
} catch {
    $failed = $true
    Write-Output "FleetFlow scheduled backup failed UTC: $([DateTimeOffset]::UtcNow.ToString('o')); $($_.Exception.Message)"
} finally {
    Stop-Transcript | Out-Null
}
if ($failed) { exit 1 }
