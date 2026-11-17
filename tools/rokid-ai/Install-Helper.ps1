param(
    [string]$Serial = '',
    [switch]$Probe,
    [switch]$Page,
    [string]$Endpoint = 'http://127.0.0.1:8766'
)
$ErrorActionPreference = 'Stop'
if (!$Serial) {
    $connected = @(& adb devices | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
    if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices. Install platform-tools and check PATH.' }
    if ($connected.Count -ne 1) { throw 'Connect exactly one authorized glasses device, or pass -Serial <adb-device-id>.' }
    $Serial = ($connected[0] -split '\s+')[0]
}
$cli = Join-Path $PSScriptRoot 'cli\node_modules\.bin\aix.cmd'
$project = Join-Path $PSScriptRoot 'helper'
$buildDirectory = Join-Path $PSScriptRoot 'build'
if (!(Test-Path -LiteralPath $cli)) {
    & npm install --prefix (Join-Path $PSScriptRoot 'cli') '@yodaos-pkg/aix-cli@0.13.0' --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the official AIX CLI. Check Node.js/npm and network connectivity.' }
}
$params = @{ endpoint = $Endpoint }
if ($Probe) {
    $params.probe = 'true'
} else {
    # This is our own local relay secret, never a Rokid account credential.
    $tokenLines = & adb -s $Serial exec-out run-as app.conversate.glass cat files/ai_bridge_token
    if ($LASTEXITCODE -ne 0) { throw 'Open Conversate on the glasses once before installing the helper.' }
    $token = ($tokenLines -join '').Trim()
    if ($token -notmatch '^[0-9a-f]{64}$') { throw 'The local Conversate connection token is invalid. Open the app and try again.' }
    $params.token = $token
}
[IO.Directory]::CreateDirectory($buildDirectory) | Out-Null
$paramsFile = Join-Path $buildDirectory 'launch-params.json'
[IO.File]::WriteAllText($paramsFile, ($params | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
try {
    & $cli check $project
    if ($LASTEXITCODE -ne 0) { throw 'AI helper source validation failed.' }
    & $cli device set-dev --serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Could not enable Rokid Developer Mode.' }
    & $cli install $project --serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the Rokid AI helper.' }
    if ($Page) {
        & $cli launch-page $project pages/bridge/index --card --params-file $paramsFile --serial $Serial
    } else {
        & $cli launch-widget $project widgets/bridge/index --params-file $paramsFile --serial $Serial
    }
    if ($LASTEXITCODE -ne 0) { throw 'Could not launch the Rokid AI helper widget.' }
    if (!$Page) {
        # The launcher must attach its Widget once; afterwards it can keep the
        # authenticated relay running while our native meeting Activity is visible.
        & adb -s $Serial shell input keyevent 224 | Out-Null
        & adb -s $Serial shell input keyevent 3 | Out-Null
        Start-Sleep -Milliseconds 1500
        & adb -s $Serial shell am start -W -n app.conversate.glass/.meeting.MeetingActivity | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not reopen the Conversate meeting screen.' }
    }
} finally {
    # The temporary launch parameters contain only our app's secret; remove them.
    if (Test-Path -LiteralPath $paramsFile) { Remove-Item -LiteralPath $paramsFile }
    $token = $null
}
