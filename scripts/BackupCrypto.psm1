Set-StrictMode -Version Latest

$script:magic = [Text.Encoding]::ASCII.GetBytes('FFBK1')
$script:aad = [Text.Encoding]::UTF8.GetBytes('FleetFlow local backup v1')

function Get-FleetFlowBackupKey([string]$KeyFile) {
    $resolved = (Resolve-Path -LiteralPath $KeyFile -ErrorAction Stop).Path
    $key = [IO.File]::ReadAllBytes($resolved)
    if ($key.Length -ne 32) { throw 'Backup key must contain exactly 32 random bytes.' }
    return $key
}

function Protect-FleetFlowArchive([string]$InputFile, [string]$OutputFile, [string]$KeyFile) {
    $key = Get-FleetFlowBackupKey $KeyFile
    $plain = [IO.File]::ReadAllBytes($InputFile)
    if ($plain.Length -gt 512MB) { throw 'Local-demo backup archive exceeds the 512 MiB encryption bound.' }
    $nonce = [byte[]]::new(12)
    [Security.Cryptography.RandomNumberGenerator]::Fill($nonce)
    $cipher = [byte[]]::new($plain.Length)
    $tag = [byte[]]::new(16)
    try {
        $aes = [Security.Cryptography.AesGcm]::new($key, 16)
        try { $aes.Encrypt($nonce, $plain, $cipher, $tag, $script:aad) }
        finally { $aes.Dispose() }
        $stream = [IO.File]::Create($OutputFile)
        try {
            $stream.Write($script:magic)
            $stream.Write($nonce)
            $stream.Write($tag)
            $stream.Write($cipher)
        } finally { $stream.Dispose() }
    } finally {
        [Array]::Clear($key, 0, $key.Length)
        [Array]::Clear($plain, 0, $plain.Length)
        [Array]::Clear($cipher, 0, $cipher.Length)
    }
}

function Unprotect-FleetFlowArchive([string]$InputFile, [string]$OutputFile, [string]$KeyFile) {
    $key = Get-FleetFlowBackupKey $KeyFile
    $data = [IO.File]::ReadAllBytes($InputFile)
    if ($data.Length -lt 33 -or $data.Length -gt (512MB + 33)) {
        throw 'Backup archive length is invalid or exceeds the local-demo bound.'
    }
    for ($i = 0; $i -lt $script:magic.Length; $i++) {
        if ($data[$i] -ne $script:magic[$i]) { throw 'Backup archive format is invalid.' }
    }
    $nonce = $data[5..16]
    $tag = $data[17..32]
    $cipher = $data[33..($data.Length - 1)]
    $plain = [byte[]]::new($cipher.Length)
    try {
        $aes = [Security.Cryptography.AesGcm]::new($key, 16)
        try { $aes.Decrypt($nonce, $cipher, $tag, $plain, $script:aad) }
        finally { $aes.Dispose() }
        [IO.File]::WriteAllBytes($OutputFile, $plain)
    } finally {
        [Array]::Clear($key, 0, $key.Length)
        [Array]::Clear($plain, 0, $plain.Length)
        [Array]::Clear($cipher, 0, $cipher.Length)
    }
}

Export-ModuleMember -Function Protect-FleetFlowArchive, Unprotect-FleetFlowArchive
