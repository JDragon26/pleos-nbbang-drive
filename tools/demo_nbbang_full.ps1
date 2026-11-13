# N빵 드라이브 시연 영상용 — 안내 + 본 시연 + 결과 읽을 시간
# ▶ 로 시작하는 줄은 녹화 스크립트가 자막으로 가져간다. 결과 자막은 화면에서 읽은 실제 금액을 쓴다.
param([switch]$Auto)
$ErrorActionPreference = 'Stop'
$tools = $PSScriptRoot
$adbExe = "$env:ANDROID_HOME\platform-tools\adb.exe"

function UiText {
    & $adbExe shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $enc = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    try { (& $adbExe shell cat /sdcard/ui.xml) -join '' } finally { [Console]::OutputEncoding = $enc }
}

Start-Sleep -Seconds 3   # 맨 앞 제목 자막을 읽을 시간
Write-Host '▶ 에뮬레이터 자동 시연 — 차량 신호를 빠르게 주입합니다'
Start-Sleep -Seconds 4

& "$tools\demo_nbbang.ps1" -Auto:$Auto

Start-Sleep -Seconds 2
Write-Host '▶ 운전자·조수석 20km / 하차 동료 12km'
Start-Sleep -Seconds 6

# 화면에 보이는 실제 금액으로 마무리 자막을 만든다
$ui = UiText
# +1,000원 같은 통행료 추가 버튼은 총액 후보에서 뺀다
$amounts = [regex]::Matches($ui, '(?<![+0-9,])([0-9][0-9,]*)원') | ForEach-Object { [int]($_.Groups[1].Value -replace ',', '') }
$total = if ($amounts) { ($amounts | Measure-Object -Maximum).Maximum } else { 0 }
$line = if ($total -gt 0) { "사람별 금액의 합 = 총 {0:n0}원" -f $total }
        else { '사람별 금액의 합 = 총액' }
Write-Host "▶ $line"
Start-Sleep -Seconds 7
