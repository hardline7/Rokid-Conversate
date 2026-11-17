$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot ('out\installer-tests\' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'distribution\Install-Conversate.ps1') -Destination $root
$apk = Join-Path $root 'fixture.apk'
[IO.File]::WriteAllBytes($apk, [Text.Encoding]::UTF8.GetBytes('Synthetic APK fixture, never installed on a device.'))
$manifest = @{ version='0.3.0-device-language'; apkName='fixture.apk'; apkSha256=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant() }
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'release-manifest.json')
@'
param([string]$Serial)
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'helper-called.txt') -Value $Serial
'@ | Set-Content -LiteralPath (Join-Path $root 'Install-Helper.ps1')
$global:InstallTestCalls = [Collections.Generic.List[string]]::new()
$global:InstallTestActive = $false
$global:InstallTestUnauthorized = $false
function global:node { $global:LASTEXITCODE = 0; 'v22.0.0' }
function global:npm { $global:LASTEXITCODE = 0 }
function global:adb {
    $line = $args -join ' '
    $global:InstallTestCalls.Add($line)
    $global:LASTEXITCODE = 0
    if ($line -eq 'devices') { return @("List of devices attached", "FAKE-GLASSES`tdevice") }
    if ($line -match 'get-state$') {
        if ($global:InstallTestUnauthorized) { return 'unauthorized' }
        return 'device'
    }
    if ($line -match 'shell pm list packages') { return 'package:app.conversate.glass' }
    if ($line -match 'exec-out run-as') {
        if ($global:InstallTestActive) { return '<map><boolean name="ownsStt" value="true" /></map>' }
        return '<map><boolean name="ownsStt" value="false" /></map>'
    }
    return 'Success'
}
function Assert-Rejected([string]$pattern) {
    $global:InstallTestCalls.Clear()
    $rejected = $false
    try { & (Join-Path $root 'Install-Conversate.ps1') }
    catch { $rejected = $_.Exception.Message -match $pattern }
    if (!$rejected) { throw "Expected installer rejection: $pattern" }
    if (@($global:InstallTestCalls | Where-Object { $_ -match ' install -r ' }).Count) {
        throw 'Installer mutated the device after a failed preflight.'
    }
}
& (Join-Path $root 'Install-Conversate.ps1')
if ((Get-Content -LiteralPath (Join-Path $root 'helper-called.txt')).Trim() -ne 'FAKE-GLASSES') { throw 'Helper did not receive the selected device.' }
if (@($global:InstallTestCalls | Where-Object { $_ -match ' install -r ' }).Count -ne 1) { throw 'Expected exactly one APK installation.' }
$global:InstallTestActive = $true
Assert-Rejected 'Recognition is still active'
$global:InstallTestActive = $false
$global:InstallTestUnauthorized = $true
Assert-Rejected 'not ready or authorized'
$global:InstallTestUnauthorized = $false
Add-Content -LiteralPath $apk 'changed'
Assert-Rejected 'checksum mismatch'
Write-Output 'PASS installer: APK + helper flow, active recognition guard, unauthorized device, checksum rejection. No real ADB commands executed.'
