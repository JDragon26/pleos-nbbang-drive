# N빵 드라이브

**같이 탄 거리만큼, 운전한 수고만큼 — 동승 이동 비용을 차가 알아서 나눠 주는 정산 미터기**

카풀·모임 이동·가족 여행에서 "기름값/전기값 누가 얼마?"를 차량 데이터로 자동 계산합니다. 좌석 탑승 센서로 누가 언제 탔다 내렸는지, 주행거리·차속으로 함께 간 거리를, 브레이크·핸들·크루즈·와이퍼로 운전 노동 강도를 재서 운전자는 덜 내도록 나눕니다.

## 주요 기능
| 기능 | 설명 | 차량 데이터 |
|---|---|---|
| 자동 탑승 기록 | 좌석별 승·하차를 감지해 사람별 구간 기록. 출발 전 잠깐 탔다 내린 자리는 제외 | 좌석 탑승(SeatOccupancy) |
| 구간 정산 | 주행 구간마다 비용을 그 순간 탄 사람끼리 분배 → 중간 하차자는 같이 탄 만큼만 | 주행거리 + 차속 적분 |
| 운전 수고 할인 | 운전자 가중치 = 1 − 할인율. 기본 20% + 운전 강도(막히는 길·골목) 최대 +15% + 비 +5% (최대 50%), 크루즈 중엔 강도 낮춤 | 브레이크, 핸들 각도, 크루즈, 와이퍼 |
| 자동 단가 | km당 = 에너지 단가 ÷ 효율. 차가 주는 전비/연비, 없으면 이번 운행 배터리 감소량으로 실측 | 전비·연비, EV 배터리 잔량 |
| 통행료·주차비 | 그 순간 탄 사람끼리 나눔 | — |
| 주행/정차 화면 전환 | 주행 중엔 미터기처럼 큰 숫자만(조작 없음), P단에선 이름·단가·정산 | 기어 |

## 실행 방법
1. Pleos Connect 에뮬레이터(API 34, Pleos Connect 2.1) 실행 후 `adb install -r -g apk/설계3.5팀_N빵드라이브_release.apk`
2. Virtual Console: `adb forward tcp:8088 tcp:8088` → 브라우저 `http://127.0.0.1:8088`
3. 앱 "N빵 드라이브" 실행 → Virtual Console 에서 좌석 탑승을 켜고 기어 D, 속도/주행거리를 올리면 금액이 올라갑니다
4. 자동 시연: `pwsh -File tools/demo_nbbang.ps1 -Auto` (시내 12km 3명 → 1명 하차 → 비 오는 고속도로 크루즈 8km 2명)

## 테스트 환경
- Android Studio 2026.2 / Gradle 8.11.1 / **JDK 21** (Studio 내장 JDK 25 로는 Gradle 8.11 이 실행되지 않음)
- Pleos Vehicle SDK 2.3.2, NaviHelper 3.0.5, Android Automotive `android.car` (차속)
- 단위 테스트: `gradlew testDebugUnitTest` (정산·수고 할인·단가·거리 계산 20개)

## APK
`apk/설계3.5팀_N빵드라이브_release.apk` (release 서명)

## 권한
| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_SEATS` | 좌석 탑승 |
| `pleos.car.permission.CAR_DRIVE` | 기어·주행거리·브레이크·핸들·크루즈 |
| `pleos.car.permission.CAR_ENERGY` | 전비·연비·배터리 잔량 |
| `pleos.car.permission.CAR_WINDOWS` | 와이퍼(비 감지) |
| `android.car.permission.CAR_SPEED` | 차속 (Pleos SDK 미제공) |

## 알려진 사항
- Vehicle SDK 2.3.2 좌석 탑승 값이 에뮬레이터에서 반대로 들어와 보정(`SEAT_SDK_INVERTED`)했습니다.
- Virtual Console 직접 운전은 주행거리를 늘리지 않아 차속 적분으로 거리를 계산합니다(실차에서도 1km 단위 주행거리 사이를 매끄럽게 채움).

## 구조
```
app/src/main/java/com/team35/nbbang/
  Meter.kt          정산 엔진 (순수 로직)
  Effort.kt         운전 수고 강도 → 할인율
  Rate.kt           효율 기반 km당 단가
  Distance.kt       주행거리 + 차속 적분
  MeterViewModel.kt 차량 SDK 연결
  MainActivity.kt   화면 (주행/정차)
```
