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
    if ($LASTEXITCODE -ne 0) { throw '공식 AIX CLI 준비 실패' }
}
$params = @{ endpoint = $Endpoint }
if ($Probe) {
    $params.probe = 'true'
} else {
    # This is our own local relay secret, never a Rokid account credential.
    $tokenLines = & adb -s $Serial exec-out run-as app.conversate.glass cat files/ai_bridge_token
    if ($LASTEXITCODE -ne 0) { throw '먼저 안경에서 Conversate 앱을 실행해 주세요.' }
    $token = ($tokenLines -join '').Trim()
    if ($token -notmatch '^[0-9a-f]{64}$') { throw 'Conversate 연결 정보가 올바르지 않습니다.' }
    $params.token = $token
}
[IO.Directory]::CreateDirectory($buildDirectory) | Out-Null
$paramsFile = Join-Path $buildDirectory 'launch-params.json'
[IO.File]::WriteAllText($paramsFile, ($params | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
try {
    & $cli check $project
    if ($LASTEXITCODE -ne 0) { throw 'AIX 소스 검사 실패' }
    & $cli device set-dev --serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Rokid 개발자 모드를 켤 수 없습니다.' }
    & $cli install $project --serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Rokid AI 도우미 설치 실패' }
    if ($Page) {
        & $cli launch-page $project pages/bridge/index --card --params-file $paramsFile --serial $Serial
    } else {
        & $cli launch-widget $project widgets/bridge/index --params-file $paramsFile --serial $Serial
    }
    if ($LASTEXITCODE -ne 0) { throw 'Rokid AI 도우미 실행 요청 실패' }
    if (!$Page) {
        # The launcher must attach its Widget once; afterwards it can keep the
        # authenticated relay running while our native meeting Activity is visible.
        & adb -s $Serial shell input keyevent 224 | Out-Null
        & adb -s $Serial shell input keyevent 3 | Out-Null
        Start-Sleep -Milliseconds 1500
        & adb -s $Serial shell am start -W -n app.conversate.glass/.meeting.MeetingActivity | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Conversate 회의 화면을 다시 열 수 없습니다.' }
    }
} finally {
    # The temporary launch parameters contain only our app's secret; remove them.
    if (Test-Path -LiteralPath $paramsFile) { Remove-Item -LiteralPath $paramsFile }
    $token = $null
}
