# 픽업 체크인

> **기다리는 사람을 안심시키는 차.**
> 학원 픽업·반려견 유치원 픽드랍처럼 여러 곳에 서서 태우고 내려주는 운전자를 위해, 차가 문·기어·위치·내비 신호로 "탔어요 · 내려드렸어요 · N분 늦어요 · 기다려요 · 앞에 N곳"을 알아채 기다리는 보호자에게 대신 연락합니다. 운전자는 주행 중 아무것도 누르지 않습니다.

현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect) 출품작

| 항목 | 내용 |
|---|---|
| 앱 이름 | 픽업 체크인 · 패키지 `com.umosone.pickupcheckin` |
| 한 줄 소개 | 정차형 픽드랍에서 탑승·인계·지연·대기·순서를 차가 판단해 보호자에게 대신 알리는 Pleos Connect 앱 |
| 팀명 | 픽미 |
| APK | [`apk/픽미_픽업체크인_release.apk`](apk/픽미_픽업체크인_release.apk) (release 빌드, 서명됨) |
| 기획서 · PRD | [`docs/기획서.md`](docs/기획서.md) · [`docs/PRD.md`](docs/PRD.md) |
| **소스 설명** | [`docs/소스설명.md`](docs/소스설명.md) — 구조도, 상태 머신, 파일별 역할, SDK 사용 위치, 테스트 목록 |
| SDK 실측 결과 | [`docs/SDK_실측결과.md`](docs/SDK_실측결과.md) |

## 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (AAOS, API 34), Android Studio. **Cold boot** 권장 |
| 화면 규격 | 2560 × 1440 px, mdpi(160dpi), 가로(landscape) — 기기 프로필 `Pleos Connect v2.1.0` 기본값 |
| SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 (ON_DEVICE) · FusedLocation 1.1.3 |
| 빌드 | JDK 17 이상, Gradle wrapper 포함 (`./gradlew`). Pleos Playground Maven 저장소는 `settings.gradle.kts`에 설정됨 |
| PC 도구 | `adb`, `python3`(표준 라이브러리만), `curl` |
| 권한 | 설치 후 `pleos.car.permission.FUSED_LOCATION` 허용 필요 (아래 명령) |
| 보호자 웹 | PC의 중계 서버(포트 8787)를 `adb reverse`로 에뮬레이터에 연결 — 에뮬레이터 인터넷 연결과 무관 |
| 가상 콘솔 | 차량 입력(기어·문)은 에뮬레이터 가상 콘솔을 씁니다. 콘솔 브라우저 세션이 열려 있어야 반영되며, 데모 스크립트가 세션을 자동 유지합니다 |

## 실행 방법

```bash
# 1) 설치 (빌드 없이)
adb install -r -g apk/픽미_픽업체크인_release.apk
adb shell pm grant com.umosone.pickupcheckin pleos.car.permission.FUSED_LOCATION
#    직접 빌드: ./gradlew :app:assembleDebug  (Android Studio로 열면 local.properties 자동 생성)
#    release 빌드: keystore.properties(storeFile·storePassword·keyAlias·keyPassword)를 루트에 두고 ./gradlew :app:assembleRelease

# 2) 보호자 웹 중계 서버 → 브라우저에서 http://localhost:8787 (보호자 화면 나란히 보기)
python3 relay/server.py

# 3) 자동 데모 (가상 콘솔 + GPS 이동 + 데모 주입, adb reverse 자동)
./scripts/demo.sh emulator-5554 4        # 학원 픽업: 지우 → 하윤 → 집
./scripts/demo_pet.sh emulator-5554 4    # 반려견 유치원 하원 드랍: 콩이 → 토리 → 유치원
adb logcat -s PICKUP                     # 판단 로그

# 단위 테스트 (상태 머신 25개)
./gradlew :app:testDebugUnitTest
```

수동으로 해 보려면: 앱 실행 → (P 상태) 픽업 시작 → 가상 콘솔에서 기어 D → `adb emu geo fix <경도> <위도>`로 지점 이동 → 기어 P → 뒷문 열고 닫기 → 기어 D. 지점 좌표는 `app/src/main/assets/plans/academy.json`.

## 시연 시나리오 (학원 픽업, 약 3분)

출발 → 지우 "이제 지우 차례" · 하윤 "앞에 1곳 남았어요" → 접근 "3분 뒤 도착" → 지점 근처 + P "차가 도착했어요" + 실내 23°C·무드등 → 뒷문 열림·닫힘 + D **"지우 탔어요"** → 정체 **"8분 늦어요"** → 보호자 "5분 늦어요" → 운전자 음성 → 대기 **"기다려요 · 3분째"**(1회) → "하윤 탔어요" → "모두 탔어요" → "집에 도착했어요"

반려견 하원: 견주 **"받았어요"** → 즉시 "콩이 내려드렸어요" · 확인성 답장("넵")은 음성 없이 화면만 · 트렁크(켄넬) 열림·닫힘 + D "토리 내려드렸어요"

## 실제 신호 vs 데모 주입

| 장면 | 신호 |
|---|---|
| 출발·정차, 탑승·하차 판정(문·트렁크) | **실제**: 가상 콘솔 → Vehicle SDK |
| 접근(1.2km)·도착(150m 근처 + 기어 P)·목적지 | **실제**: `adb emu geo fix` → FusedLocation 지오펜스 + 기어 |
| 실내 준비 (온도·무드등) | **실제**: HVAC·Light set → get 재확인 |
| 경유지 경로 (2곳씩 롤링) | **실제**: NaviHelper `requestRouteWithWaypoints` |
| 차막힘 지연 | **데모 주입** — 에뮬레이터 내비는 목업이라 정체 값이 변하지 않음. 실차는 `getWaypointInfo().remainTime`·`trafficStatus` |
| 대기 3분 경과 | **데모 주입** (시간 앞당김) |

데모 명령: `adb shell am start-foreground-service -n com.umosone.pickupcheckin/.car.PickupService --es demo "delay:8|wait:3|soon|arrive|dest"`

## 판정 규칙 (요약)

- 탑승·하차: 지점 근처 + P에서 조수석·뒷문(켄넬 플랜은 트렁크 포함) 열림→닫힘 = 후보 → 다시 열리지 않고 D로 출발하면 확정. 주행 중 문 이벤트·운전석 문은 무시
- 한 지점 여러 명: 보호자가 같으면(형제) 함께 확정, 다르면 자동 확정하지 않고 다음 P에서 운전자에게 한 번 확인
- 드랍 인계: 보호자 "받았어요"가 오면 즉시 확정
- 지연: 기준 ETA보다 5분 이상 늦으면 해당 보호자에게만, 3분 이상 더 늦어질 때만 재알림, 곧 도착 이후 중단
- 개인정보: 다른 가정의 이름·주소는 보내지 않고 순서는 "앞에 N곳"만. 아이·반려견 개인의 위치는 따로 수집하지 않음

## 구성

| 경로 | 내용 |
|---|---|
| `app/src/main/java/.../core/` | 순수 Kotlin 상태 머신 `PickupEngine` — 안드로이드 의존성 없음 |
| `app/src/test/` | 단위 테스트 25개 |
| `app/src/main/java/.../car/` | SDK 어댑터: `VehicleSignals`, `NaviSource`, `GeofenceSource`, `Speaker`, 포그라운드 `PickupService` |
| `app/src/main/java/.../ui/MainActivity.kt` | 차량 화면 (주행 중 입력 없음) + 발표자용 DEMO 패널 |
| `app/src/main/assets/plans/` | 데모 플랜: `academy.json`(학원), `pet.json`(반려견), `friends.json` |
| `relay/` | 중계 서버 + 보호자 모바일 웹 (설치 없음) |
| `scripts/` | 에뮬레이터 자동 데모 |

## 참고
- `PickupService`의 `exported="true"`는 데모 스크립트(adb)용입니다.
- 매니페스트에는 SDK 문서에 있는 퍼미션만 선언했습니다 (`PRIVATE_CAR_*` 미사용).
