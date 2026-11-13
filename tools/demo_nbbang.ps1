# N빵 드라이브 시연 시나리오 — Virtual Console(127.0.0.1:8088) 값을 순서대로 바꾼다.
# 먼저 2_start_emulator.bat 로 에뮬레이터를 켜고 앱을 띄운 뒤 실행:
#   pwsh -File tools\demo_nbbang.ps1          (단계마다 Enter)
#   pwsh -File tools\demo_nbbang.ps1 -Auto    (2초 간격 자동 진행, 영상 녹화용)
#
# 줄거리: 동료 3명 카풀. 시내(가다 서다) 12km → 뒷좌석 동료 하차 → 비 오는 고속도로 크루즈 8km.
param([switch]$Auto, [double]$Delay = 4)

$vc = 'http://127.0.0.1:8088'

# Virtual Console 은 브라우저 세션 신호가 3초 안에 없으면 값을 안 받는다 (emulator_inactive).
function Alive { Invoke-WebRequest -Method Post -UseBasicParsing "$vc/browser/session?client=nbbang-demo" | Out-Null }
function Send($path, $query) {
    Alive
    Invoke-WebRequest -Method Post -UseBasicParsing "$vc$path`?$query" | Out-Null
}
function Seat($area, $on) { Send '/vehicle/seat' "kind=occupied&area=$area&value=$($on.ToString().ToLower())" }
function Gear($g) { Send '/vehicle/gear' "gear=$g" }
function Cruise($state) { Send '/vehicle/driving-mode' "ad_mode_state=$state" }
function Wiper($mode) { Send '/vehicle/body' "front_wiper=$mode" }
function Battery($kwh) { Send '/vehicle/ev-battery' ("field=battery_level&value={0:0.0}" -f $kwh) }

# 주행거리는 줄일 수 없다 → 지금 값에서 출발해 더해 간다.
$state = (Invoke-WebRequest -UseBasicParsing "$vc/vehicle/state").Content | ConvertFrom-Json
$odoBase = $state.odometer.traveled_distance_km
$kwh = [math]::Max(40, $state.ev_battery.current_energy_kwh)
function Odo($km) { Send '/vehicle/odometer' "traveled_distance_km=$($odoBase + $km)" }

# 4km 달리기: 배터리를 효율만큼 줄이고, 시내면 브레이크·핸들을 많이 쓴다.
function Drive4km([switch]$City, [double]$KmPerKwh = 5.5) {
    if ($City) {
        foreach ($i in 1..16) {                   # km당 4번 브레이크 → 가다 서다
            Send '/vehicle/controls' 'brake=60'; Start-Sleep -Milliseconds 120
            Send '/vehicle/controls' 'brake=0'; Start-Sleep -Milliseconds 120
            if ($i % 4 -eq 0) { Send '/vehicle/controls' 'steering=180'; Start-Sleep -Milliseconds 120; Send '/vehicle/controls' 'steering=0' }
        }
    }
    $script:kwh -= 4 / $KmPerKwh
    Battery $script:kwh
    $script:km += 4
    Odo $script:km
}
function Step($msg) {
    Write-Host "`n▶ $msg" -ForegroundColor Yellow
    if ($Auto) { Start-Sleep -Seconds $Delay } else { Read-Host '   Enter' | Out-Null }
}

# ── 영상 자막용: 화면이 실제로 바뀐 뒤에 설명을 찍는다 ────────────────────────
$adbExe = "$env:ANDROID_HOME\platform-tools\adb.exe"
function UiText {
    & $adbExe shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $enc = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    try { (& $adbExe shell cat /sdcard/ui.xml) -join '' } finally { [Console]::OutputEncoding = $enc }
}
function SayWhenUi([string]$Pattern, [string]$Text, [double]$TimeoutSec = 15) {
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
        if ((UiText) -match $Pattern) { break }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "▶ $Text" -ForegroundColor Yellow
}

$km = 0
Step '준비 — 주차(P), 운전자만 탑승'
Gear 'Park'; Odo 0; Battery $kwh; Cruise 'ready'; Wiper 'Off'
Send '/vehicle/body' 'frunk=false&trunk=false'
Seat 'driver' $true; Seat 'passenger' $false; Seat 'rear_left' $false; Seat 'rear_right' $false

Step '동료 2명 탑승 — 좌석 센서가 자동 인식'
Seat 'passenger' $true; Seat 'rear_left' $true

Write-Host '▶ 이제 출발(D) 한다' -ForegroundColor Yellow
Gear 'Drive'
SayWhenUi '주행 중' '주행 중엔 큰 숫자만 (조작 없음)'
Start-Sleep -Seconds 3

foreach ($n in 1..3) { Step "이제 시내 주행 — 누적 $($km + 4)km 까지"; Drive4km -City }

Write-Host '▶ 이제 정차(P) · 동료 하차' -ForegroundColor Yellow
Gear 'Park'; Seat 'rear_left' $false
SayWhenUi '지점에서 내림' '내린 동료 금액은 여기서 멈춘다'
Start-Sleep -Seconds 3

Step '비 오는 고속도로 · 크루즈 켬'
Gear 'Drive'; Wiper 'Low'; Cruise 'full_autonomous'

foreach ($n in 1..2) { Step "이제 고속도로 주행 — 누적 $($km + 4)km 까지"; Drive4km -KmPerKwh 6.5 }

Write-Host '▶ 이제 도착(P) 해서 정산한다' -ForegroundColor Yellow
Gear 'Park'; Cruise 'ready'; Wiper 'Off'
SayWhenUi '정산 끝' '함께 간 거리만큼 금액 확정'
Start-Sleep -Seconds 3
Write-Host "`n끝. 시내 12km 3명(운전자 할인 큼) + 고속도로 8km 2명(크루즈라 할인 작음, 비 +5%)" -ForegroundColor Green
