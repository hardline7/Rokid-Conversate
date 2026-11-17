param(
    [string]$Serial = '1901092548000028',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'out\stt')
)
$ErrorActionPreference = 'Stop'
$names = & adb -s $Serial shell run-as app.conversate.glass ls files/transcripts
if ($LASTEXITCODE -ne 0) { throw '안경의 전사 폴더를 읽을 수 없습니다.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
foreach ($name in $names) {
    $name = $name.Trim()
    if ($name -notmatch '^\d+-[0-9a-f-]+\.jsonl$') { continue }
    $lines = & adb -s $Serial shell run-as app.conversate.glass cat "files/transcripts/$name"
    if ($LASTEXITCODE -ne 0) { throw "전사 읽기 실패: $name" }
    [IO.File]::WriteAllLines((Join-Path $OutputDirectory $name), [string[]]$lines, [Text.UTF8Encoding]::new($false))
    $latest = [ordered]@{}
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $row = $line | ConvertFrom-Json
        $latest["$($row.audio_path):$($row.number)"] = $row
    }
    $text = @('# Rokid STT transcript', '', '확정된 문장입니다. 화자 구분은 검증되지 않았습니다.', '')
    $text += @($latest.Values | ForEach-Object { $_.content; '' })
    $markdown = Join-Path $OutputDirectory ($name -replace '\.jsonl$', '.md')
    [IO.File]::WriteAllLines($markdown, [string[]]$text, [Text.UTF8Encoding]::new($false))
    Write-Output $markdown
}
