$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'BackupRetention.psm1') -Force

$project = 'fleetflow-phase11-demo'
$old = 'fleetflow-fleetflow-phase11-demo-20260901T000000Z.ffbackup'
$names = @(
    $old,
    'fleetflow-fleetflow-phase11-demo-20261008T000000Z.ffbackup',
    'fleetflow-fleetflow-phase11-other-20260901T000000Z.ffbackup',
    'fleetflow-fleetflow-phase11-demo-20260999T000000Z.ffbackup',
    'fleetflow-fleetflow-phase11-demo-20260901T000000Z.ffbackup.tmp',
    '../fleetflow-fleetflow-phase11-demo-20260901T000000Z.ffbackup'
)
$cutoff = [DateTime]::SpecifyKind([DateTime]'2026-10-01T00:00:00', [DateTimeKind]::Utc)
$expired = @(Get-FleetFlowExpiredRemoteArchives -Names $names -Project $project -CutoffUtc $cutoff)
if ($expired.Count -ne 1 -or $expired[0] -cne $old) {
    throw 'Remote retention selector crossed the project, age, or filename boundary.'
}
Write-Output 'Remote retention selector passed: only one exact, expired project archive selected.'
