param(
    [string]$Serial = '',
    [switch]$SkipAiHelper
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Set-StrictMode -Version Latest

Write-Host 'Rokid Conversate - APK and Rokid AI helper installer'
Write-Host 'End any meeting before installing or updating. Keep the paired Rokid phone connected.'

if (!(Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'ADB not found. Install Android SDK Platform-Tools and add its folder to PATH, then reopen PowerShell.'
}
if (!$SkipAiHelper) {
    if (!(Get-Command node -ErrorAction SilentlyContinue) -or !(Get-Command npm -ErrorAction SilentlyContinue)) {
        throw 'Install Node.js 20 or newer (including npm), then reopen PowerShell.'
    }
    $nodeVersion = & node --version
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 20) {
        throw 'The Rokid AI helper installer requires Node.js 20 or newer.'
    }
}

$manifestPath = Join-Path $PSScriptRoot 'release-manifest.json'
if (!(Test-Path -LiteralPath $manifestPath)) { throw 'Extract the complete Windows Setup ZIP before running this installer.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$apkName = [string]$manifest.apkName
if ([IO.Path]::GetFileName($apkName) -ne $apkName -or !$apkName.EndsWith('.apk')) { throw 'Invalid APK name in release manifest.' }
$apk = Join-Path $PSScriptRoot $apkName
if (!(Test-Path -LiteralPath $apk)) { throw 'APK missing. Extract the complete Windows Setup ZIP again.' }
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$manifest.apkSha256) {
    throw 'APK checksum mismatch. Download the release again; installation was not started.'
}
$helper = Join-Path $PSScriptRoot 'Install-Helper.ps1'
if (!$SkipAiHelper -and !(Test-Path -LiteralPath $helper)) { throw 'AI helper installer missing from the extracted package.' }

if (!$Serial) {
    $connected = @(& adb devices | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
    if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
    if ($connected.Count -ne 1) {
        throw 'Connect exactly one authorized Rokid glasses device, or pass -Serial <device-id>. Accept the USB debugging prompt on the glasses.'
    }
    $Serial = ($connected[0] -split '\s+')[0]
}
$deviceState = & adb -s $Serial get-state
if ($LASTEXITCODE -ne 0 -or ($deviceState -join '').Trim() -ne 'device') { throw 'The selected ADB device is not ready or authorized.' }

# Stop before touching an APK if this app still owns a native recognition stream.
# This reads only our own non-secret recovery flag, never a Rokid credential.
$existing = & adb -s $Serial shell pm list packages app.conversate.glass 2>$null
if ($LASTEXITCODE -eq 0 -and ($existing -join '').Contains('package:')) {
    $recovery = & adb -s $Serial exec-out run-as app.conversate.glass cat shared_prefs/native_recovery.xml 2>$null
    if ($LASTEXITCODE -eq 0) {
        [xml]$recoveryXml = $recovery -join "`n"
        $ownsStt = $recoveryXml.SelectSingleNode('/map/boolean[@name="ownsStt"]/@value')
        if ($ownsStt -and $ownsStt.Value -eq 'true') {
            throw 'Recognition is still active or awaiting a stop acknowledgement. End the meeting and close Rokid captions before updating.'
        }
    }
}

Write-Host "Installing Conversate $($manifest.version) on the selected glasses..."
& adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw 'APK installation failed. If Android reports a signing mismatch, export existing records before considering removal; do not uninstall to fix it automatically.'
}
& adb -s $Serial shell input keyevent 224 | Out-Null
& adb -s $Serial shell am start -W -n app.conversate.glass/.meeting.MeetingActivity
if ($LASTEXITCODE -ne 0) { throw 'APK installed, but the meeting screen could not be opened.' }

if (!$SkipAiHelper) {
    & $helper -Serial $Serial
}
Write-Host ''
Write-Host 'Setup complete. Wait for Rokid AI connected on the glasses.'
Write-Host 'Open the HTTPS address displayed on the glasses and enter their current six-digit PIN.'
Write-Host 'No meeting was started by this installer. Tap twice on the glasses when ready to start.'
