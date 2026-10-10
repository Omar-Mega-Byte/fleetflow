Set-StrictMode -Version Latest

function Get-FleetFlowExpiredRemoteArchives {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [Parameter(Mandatory)][string]$Project,
        [Parameter(Mandatory)][DateTime]$CutoffUtc
    )
    if ($Project -cnotmatch '^[a-z][a-z0-9-]{1,40}$') { throw 'Invalid Compose project name.' }
    $archivePattern = '^fleetflow-' + [regex]::Escape($Project) + '-(\d{8}T\d{6}Z)\.ffbackup$'
    foreach ($name in $Names) {
        if ($name -cnotmatch $archivePattern) { continue }
        $archiveTime = [DateTime]::MinValue
        if (-not [DateTime]::TryParseExact($Matches[1], 'yyyyMMddTHHmmssZ',
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AssumeUniversal -bor
                [Globalization.DateTimeStyles]::AdjustToUniversal,
                [ref]$archiveTime)) { continue }
        if ($archiveTime -lt $CutoffUtc) { $name }
    }
}

Export-ModuleMember -Function Get-FleetFlowExpiredRemoteArchives
