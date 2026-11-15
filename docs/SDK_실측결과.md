# Pleos Connect SDK API 분석·테스트 결과

> HMG 차량용 앱 개발 공모전 (https://appdevchallenge.auto-hmg.io/) 참가자 가이드 기반
> 테스트일: 2026-11-09 · 테스트 앱·원본 로그: 팀 내부 SDK 전수 테스트 앱 (별도 보관, 아래 명령의 `pleos-api-test`)

## 0. 한눈에 보기

| SDK | 버전 | 결과 | 한 줄 요약 |
|---|---|---|---|
| Vehicle | 2.3.2 | ✅ 대부분 동작 | get/check 241개 중 **171개 성공**, set→get 왕복·콜백 정상 (단 **Virtual Console 세션이 열려 있어야** 함) |
| NaviHelper | 3.0.5 | ✅ 동작 (목업) | 경로·경유지·취소 이벤트 정상. 에뮬레이터 내비는 **좌표를 무시한 가짜 경로**를 돌려줌 |
| TextToSpeech | 3.3.1 | ✅ ON_DEVICE / HYBRID | SERVER는 앱 등록 없으면 `onError(UNKNOWN)` |
| SpeechToText | 3.3.1 | ✅ ON_DEVICE(PCM 주입) | 한국어 문장 정확히 인식. 마이크 경로는 에뮬레이터 수동 설정 필요 |
| FusedLocation | 1.1.3 | ✅ 동작 | 1Hz 위치 업데이트, `geo fix` 반영, 지오펜스 ENTER/EXIT 정상 |
| LLM (문서 미공개) | 2.1.3.2 | ❌ 에뮬레이터에서 미동작 | ON_DEVICE "Model is not running!", SERVER는 무응답 |

### 꼭 알아야 할 함정 TOP 8

1. **Virtual Console을 브라우저로 열어 둬야 차량 값이 실제로 반영된다.** 콘솔 세션이 없으면 `set`은 `onComplete`가 오는데도 직후 `get`은 이전 값을 돌려준다(도어 잠금·HVAC 전원·조명·열선·충전구 전부). 콘솔을 열면 모두 정상 왕복. (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)
2. **`set`의 `onComplete`는 "요청한 값"을 그대로 돌려줄 뿐 적용을 보장하지 않는다.** 충전 한도 42%(허용 목록 50~100 밖)도 `OK(42.0)`로 온다. 실제 상태는 `get`이나 리스너로 확인할 것.
3. **Area는 단일 좌석이 아니라 `check…Capabilities`가 돌려준 "묶음 영역"을 그대로 써야 한다.** 예: HVAC 전원은 `SeatArea(ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT)`, 팬 속도는 `SeatArea(ROW_1_LEFT, ROW_1_RIGHT)`, 도어 잠금은 `DoorArea(ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT)`. 단일 영역으로 부르면 `area ID: 0x1 not supported`. 이렇게 고치자 HVAC 10개와 앰비언트 4개 API가 추가로 성공했다.
4. **문서의 `VehicleException`은 AAR에 없다.** 2.3.2에서는 난독화된 `ai.pleos.playground.vehicle.callback.exception.a`이고 `errorCode` 대신 `a()`로 꺼내야 한다. 게다가 실패의 대부분(약 87%)은 VehicleException이 아니라 `java.lang.Exception`(안에 `IllegalArgumentException`/`PropertyNotAvailableException`)으로 온다. 결론: **`e is VehicleException` 분기에 의존하지 말고 `e.message`로 처리**할 것.
5. **문서 예제 시그니처가 실제 SDK와 다르다.** `registerDoorPosition(listener)` → 실제 `registerDoorPosition(DoorArea, listener)`, `setDoorPosition(area, value, onFailed)` → 실제 `setDoorPosition(area, value, onComplete(area, value), onFailed)`. 문서에 없는 `Gear` 도메인(`vehicle.getGear()`)도 있다.
6. **영역이 틀려도 `register…`는 예외 없이 성공하고**, 실패는 나중에 리스너 `onFailed`로 온다.
7. **단위·부호가 콘솔 표시와 다르다**: 조향각 부호 반전(콘솔 90° → SDK `-90.0`), 창문 위치 스케일(콘솔 70 → SDK `7`, 범위 0~10), 배터리 레벨 Wh(콘솔 60kWh → `60000.0`).
8. **문서에 없는 `pleos.car.permission.PRIVATE_CAR_*`(protectionLevel=normal)** 를 선언해야 `CarInfo.getCapabilityInfo`, `Safety.getTelltalesInfo/checkTelltalesCapabilities`, `Tire.registerTirePressure`가 풀린다. 단, **App Market 심사에서 미승인 퍼미션은 반려**되므로 출품 앱에는 쓰지 말 것.

---

## 1. 테스트 환경

| 항목 | 값 |
|---|---|
| 호스트 | macOS (Apple M4, 32GB), Android Studio, JDK 21 |
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34, arm64) · `Pleos/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0` |
| AVD | `Pleos_Connect_v2_1` · RAM 4GB · Cold boot · GPU host |
| 빌드 | AGP 9.0.0, Gradle 9.1.0, Kotlin 2.2.10, minSdk 28, targetSdk 34 |
| SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (로그인 없이 받아짐) |

### 에뮬레이터 설치 (CLI로 재현 가능)

```bash
# 1) ~/.android/repositories.cfg 에 Pleos 업데이트 사이트 등록
src00=https\://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml
# 2) 이미지 설치 (42dot SDK 라이선스 동의 필요, 약 4.7GB)
sdkmanager --install "system-images;android-34;pleos_car_emulator;arm64-v8a"
# 3) AVD 생성 (기기 정의: Pleos_Connect_v2_0) 후 config.ini 에 fastboot.forceColdBoot=yes, hw.ramSize=4096
avdmanager create avd -n Pleos_Connect_v2_1 -k "system-images;android-34;pleos_car_emulator;arm64-v8a" -d Pleos_Connect_v2_0
```

> Quick boot로 띄우면 VHAL을 쓸 수 없다(공식 가이드). 반드시 Cold boot.

### 테스트 앱 사용법

```bash
cd pleos-api-test && ./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.umosone.pleostest pleos.car.permission.FUSED_LOCATION   # dangerous 권한
adb shell am start -n com.umosone.pleostest/.MainActivity --es suite "veh-get,veh-set" --ei listen 30
adb logcat -s PLEOS     # 결과: SUITE|TARGET|API|STATUS|DETAIL
```

| suite | 내용 |
|---|---|
| `veh-get` | 리플렉션으로 모든 도메인의 `get/is/has/check*` API를 모든 영역에 대해 자동 호출 |
| `veh-set` | 대표 제어 API set → get 왕복, 범위 밖 값 등 음성 테스트 |
| `veh-cb` | 모든 `register*` 리스너를 모든 후보 영역에 등록 후 이벤트 로깅 (`results/vc_script.sh`로 콘솔 조작) |
| `navi` | NaviHelper 정보 조회 → 경로 → 경유지 → 옵션 → 재탐색 → 취소 |
| `tts:<MODE>` / `stt:<MODE>:<pcm\|mic>` | 음성 합성/인식 |
| `loc` | FusedLocation 1회 조회·업데이트·지오펜스 |
| `llm:<MODE>` | LLM SDK |

### Virtual Console HTTP API (자동화용으로 발견)

콘솔 웹페이지가 쓰는 API를 그대로 호출할 수 있다. **브라우저 세션 하트비트가 살아 있어야 쓰기가 먹힌다**(없으면 `{"error":"emulator_inactive"}`).

```bash
adb forward tcp:8088 tcp:8088
while true; do curl -s -X POST "http://127.0.0.1:8088/browser/session?client=cli" >/dev/null; sleep 1; done &   # 세션 유지
curl "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
curl "http://127.0.0.1:8088/vehicle/body?door_fl=true"
curl "http://127.0.0.1:8088/vehicle/ev-battery?field=soc&value=42"
curl "http://127.0.0.1:8088/vehicle/state"      # 전체 상태
curl "http://127.0.0.1:8088/vehicle/catalog"    # 63개 기능의 엔드포인트·필드·VHAL 프로퍼티 매핑
```



---

## 2. Vehicle SDK

### 2-1. 구조 (AAR 분석)

- 진입점 `Vehicle(context)` → `initialize()` → `getXxx()`로 도메인 객체 → `release()`
- 도메인 20개: Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, **Gear(문서 누락)**, HVAC, Light, Odometer, Safety, Seat, SideMirror, SteeringWheel, Tire, Toll, TurnSignal, Window, Wiper
- 공개 메서드 약 540개 (register/unregister 184개 포함)
- 모든 호출은 IPC 비동기: `onComplete` / `onFailed` 람다
- 퍼미션: `CAR_*`(조회), `CONTROL_CAR_*`(제어), 그리고 문서에 없는 `PRIVATE_CAR_*`

### 2-2. get / check 스윕 결과 — 241개 중 171개 성공

실패 70개의 원인:

| 원인 | 의미 |
|---|---|
| `ERROR_NOT_SUPPORTED_PROPERTY` | check 단계에서 미지원 (Fuel 전체, Odometer Trip*, EV 효율, 안개등 등) |
| `property ID is not in carPropertyConfig list` | 에뮬레이터 VHAL에 프로퍼티가 정의되지 않음 |
| `AREA_NOT_SUPPORTED` | 잘못된 영역 (capability 영역을 쓰면 해결) |
| `SecurityException: … need Permission` | PRIVATE_* 권한 필요 |

**에뮬레이터에서 못 쓰는 기능**: Fuel 전체(EV 차량 설정), Trip 주행거리/시간, EV 효율(전비), 드라이브 모드 상태, 안개등, 앰비언트 드라이브모드 연동, 엔진오일/브레이크액/변속기 오일, 워셔액, 에어백 전개, 안전벨트 미착용 경고, 스마트글래스, ETCS 음량, 타이어 공기압(`getTirePressure`; 저압 경고 `getLowTirePressure`는 됨), Max AC.

**쓸 만한 기본값 예시**: 기어 `GEAR_PARK`, 배터리 SOC 100%, 용량 77,400Wh, 주행가능거리 500,000m, 실내온도 19.1°C, HVAC 설정 15~30°C, 팬 1~8, 크루즈 `ENABLED` / 13.89m/s, 충전 한도 허용값 [50, 55, …, 100], 시트 열선 -3~3(앞좌석, 음수는 통풍), 창문 위치 0~10.

전체 표는 맨 아래 **부록 A** 참고.

### 2-3. set → get 왕복 (Virtual Console 열린 상태)

| API | 요청 | onComplete | 직후 get | 비고 |
|---|---|---|---|---|
| `setDoorLock` | 단일 `ROW_1_LEFT` | ❌ `area ID: 0x1 not supported` | — | 묶음 영역 필요 |
| `setDoorLock` | 묶음 4도어, false | ✅ | ✅ false | 콘솔 없을 땐 true로 남음 |
| `setDoorPosition` | FL, 1 | ✅ | ✅ 1 | |
| `setGloveBoxLock` | false | ✅ | ✅ false | |
| `setHvacPower` | 전체 영역, true | ✅ | ✅ true | |
| `setHvacFanSpeed` | 앞좌석, 5 | ✅ | ✅ 5 | 콘솔 없을 땐 `PropertyAsyncError` |
| `setHvacFanSpeed` | 99 | ❌ `must be less than or equal to … max` | | 범위 검증 있음 |
| `setHvacTemperature` | ROW1L, 22.5°C | ✅ | ✅ 22.5 | 콘솔 없을 땐 get이 `PropertyNotAvailableException` |
| `setWindowPosition` | 5 / 50 | ✅ / ❌ 범위 초과 | ✅ 5 | 범위 0~10 |
| `setAmbientLightBrightness` | 전체, 70 | ✅ | ✅ 70 | |
| `setAmbientLightBrightnessColor` | 0xFF0000 | ✅ | ✅ 16711680 | |
| `setCabinLightSwitch` | ON | ✅ | ✅ ON | |
| `setHvacSeatTemperature` | ROW1L, 2 | ✅ | ✅ 2 | |
| `setHvacSeatVentilation` | ROW1L, 1 | ✅ | ✅ 1 | |
| `setEvChargePercentLimit` | 80 / **42** | ✅ / **✅(!)** | | 허용 목록 밖인데도 OK |
| `setEvChargePortOpen` | true | ✅ | ✅ true | |

### 2-4. 콜백 (리스너 90종 × 영역 → 517개 등록)

- 등록 직후 현재값이 1회 전달된다.
- **지연 약 2~26ms** (콘솔 조작 → `onXxxUpdated`, 시계 오프셋 -3.79초 보정)
- 주기적으로 계속 오는 것(값 변화 없어도 1~2.5초 간격): `SteeringWheelAngle`, `DistanceDriven`, `RangeRemaining`, `EvBatteryLevel` → UI 갱신 시 디바운스 필요

| 콘솔 조작 | 받은 이벤트 | 지연 |
|---|---|---|
| 기어 R / D / P | `GearSelectionListener.onSelected` GEAR_REVERSE / DRIVE / PARK | 2~18ms |
| 운전석 도어 열기/닫기 | `DoorPositionListener@ROW_1_LEFT` 1 / 0 | 10~15ms |
| 전체 도어 잠금 해제/잠금 | `DoorLockStateListener@묶음` false / true | 9ms |
| 브레이크 80% / 0% | `BrakePedalStatusListener` true / false | 4~9ms |
| 조향 90° / -45° | `SteeringWheelAngleListener` **-90.0 / 45.0 (부호 반전)** | 48~108ms |
| 방향지시등 우 / 끔 | `TurnSignalStateListener` RIGHT / OFF | 6~11ms |
| SOC 77 | `EvBatteryStateOfChargeListener` 77 | 9ms |
| 배터리 레벨 60kWh | `EvBatteryLevelListener` 60000.0 (Wh) | 주기 이벤트로 반영 |
| 충전구 열기/닫기 | `EvChargePortOpenListener` true / false | 8ms |
| 팬 2 | `HvacFanSpeedListener@ROW_1_LEFT,ROW_1_RIGHT` 2 | 9ms |
| 운전석 온도 27 | `HvacTemperatureListener` 27.0, `HvacTemperatureValueListener` (27.0, 82.0)=°C,°F | 13ms |
| HVAC 전원 끔/켬 | `HvacPowerStateListener@전체` false / true | 10ms |
| 크루즈 목표속도 15 | `CruiseControlTargetSpeedListener` 15.0 | 11ms |
| 창문 70 / 0 | `WindowPositionListener@ROW_1_LEFT` **7** / 0 | 6ms |
| 하이빔 켬/끔 | `LightStateListener`(HighBeam) ON / OFF | 14~26ms |
| 앰비언트 색 #2979ff | `AmbientLightBrightnessColorListener` 2718207 | 10ms |
| 핸들 열선 1 | `HvacSteeringWheelHeatStateListener` 1 | 7ms |
| 주행거리 1234km | `DistanceDrivenListener` 1234.0 | 7ms |
| 주행가능거리 99,000m | `RangeRemainingListener` 99000.0 | 주기 이벤트로 반영 |
| 전면 와이퍼 High / Off | `WiperStateListener@FRONT_WINDSHIELD` ON / OFF | 7~9ms |

**이벤트가 안 온 콘솔 조작**: 크루즈 상태(lateral), 창문 잠금, 앰비언트 밝기/모드, 실내등, 독서등, 시트 착좌, 시트 슬라이드, 글로브박스 열림(SDK는 "잠금" 상태만 있음), 경고등(telltale·권한 필요), 타이어 공기압(권한 필요). 이 기능들은 앱이 리스너에만 의존하지 말고 필요하면 `get`으로 폴링할 것.

---

## 3. NaviHelper SDK

결과: 초기화 `onConnected` 정상, 모든 정보 조회 API가 이벤트로 응답.

| 호출 | 받은 이벤트 (순서대로) |
|---|---|
| `getCurrentLocationInfo` | `onCurrentLocationInfo` (37.4127, 127.0947, "수정구 시흥동") — 에뮬레이터 내비 위치는 **판교 고정** |
| `getBookmarkInfo` | HOME/OFFICE 빈 항목 |
| `getRecentDestinationInfo` | `[]` |
| `getRouteStateInfo` | `naviMode=SAFE_GUIDING, isRouting=false, routeOption=RECOMMENDED` |
| `getChargerOperatorInfo` | 충전 사업자 목록 (현대 E-pit, GS차지비, 에버온, 환경부, 한전, 채비 등) |
| `requestRoute(42dot)` | `onRouteStarted(60s, 50m)` → **`onRouteEnded`** → `onNavigationStatus(ROUTE_DRIVE)` → `onRouteStateInfo(ROUTE_GUIDING)` → `onTBTInfo` → `onDrivingInfo` |
| `getDestinationInfo` (안내 중) | 이름·poiId는 맞지만 **좌표/주소가 요청과 다름** (37.4129, 127.0938, "수정구 시흥동") |
| `addWaypoint(FIRST)` | `onRouteCancelled` → `FREE_DRIVE` → … → `onWaypointChanged(FIRST, ADD)` → `onRouteEnded` → `ROUTE_DRIVE` (취소 후 재경로) |
| `getWaypointInfo` | 경유지 1개 |
| `removeWaypoint(FIRST)` | `onWaypointChanged(FIRST, REMOVE)` (마찬가지로 취소 후 재경로) |
| `changeRouteOption(HIGHWAY)` | `onDrivingInfo`, `onTBTInfo`만 옴. **`onRouteOptionChanged` 안 옴** |
| `requestReRoute` | **이벤트 없음** (`onReRouteEnded` 안 옴) |
| `cancelRoute` | `onRouteCancelled` → `FREE_DRIVE` |
| 가까운 목적지 | 에러 대신 12km/요금 700원 경로가 나옴 → 목업이 좌표를 무시함 |

주의:
- 문서에 있는 **`onRoutingCompleted`, `onDestinationChanged`는 한 번도 오지 않았다.** 대신 `onRouteStarted` → `onRouteEnded` → `onNavigationStatus(ROUTE_DRIVE)`로 판단할 것.
- `onCurrentRoadSpeedLimit(0)`이 약 1초마다 계속 온다.
- `NaviErrorCode` 오류는 재현하지 못했다(목업이 항상 성공).
- 미테스트: `requestNavigationScreen`, `requestTrip`, `requestRouteWithWaypoints`.

---

## 4. TextToSpeech SDK

| 모드 | initialize | speak("안녕하세요…") | 조각 전송 + stop() |
|---|---|---|---|
| ON_DEVICE | ✅ | `onReady → onStart → onDone` (4.6초) | `onStart → onStop` ✅ |
| HYBRID (앱 등록 없음) | ✅ | `onReady → onStart → onDone` (3.4초, 온디바이스로 대체된 것으로 보임) | ✅ |
| SERVER (앱 등록 없음) | ✅ | `onReady → onError(UNKNOWN)` | `onError(UNKNOWN)` |

- 앱 등록 없이 SERVER 모드를 쓰면 문서의 `UNAUTHENTICATED`가 아니라 `UNKNOWN`이 온다.
- 실제 소리가 났는지는 이벤트로만 확인했다(호스트 스피커로 직접 듣지는 않음).

## 5. SpeechToText SDK

| 테스트 | 결과 |
|---|---|
| ON_DEVICE + `sendAudio` (macOS `say`로 만든 16kHz/16bit/mono PCM "오늘 날씨 어때? 가까운 충전소 알려줘.") | ✅ 최종 결과 **"오늘 날씨 어때 가까운 충전소 알려 줘"** |
| HYBRID + `sendAudio` | 에러 없이 무시됨 → 15초 타임아웃 (문서상 ON_DEVICE 전용인데 `NOT_SUPPORTED_API`도 안 옴) |
| ON_DEVICE + `request()` (마이크) | `onReady`까지만 옴. 호스트 소리가 들어가지 않음 → Extended Controls ▸ Microphone ▸ "Virtual microphone uses host audio input" 수동 설정 필요 |

이벤트 순서 주의 (ON_DEVICE):
- 부분 결과마다 `onStartedRecognition`이 **반복해서** 온다.
- **`onEndedRecognition`이 최종 `onUpdated(completed=true)`보다 먼저 온다.** `onEndedRecognition`에서 정리해 버리면 최종 문장을 놓친다.
- `completeAudioSend()` 호출 전에 EPD가 끝을 감지해 결과가 먼저 나온다.
- `onReady`는 PCM 모드에선 안 오고, `onUpdatedRms`도 오지 않았다.

## 6. FusedLocation SDK

| 항목 | 결과 |
|---|---|
| `isSupported()` | true |
| `getLastKnownLocation` | 37.422, -122.084 (Android 에뮬레이터 기본 GPS = 구글 본사). **내비가 알려주는 판교 위치와 다르다** |
| `registerFusedLocationCallback` | 약 **1Hz**로 계속 수신 (40초 동안 38건), `adb emu geo fix` 즉시 반영 |
| `addProximityAlert` (42dot, 반경 300m) | `onRegistered(uuid)` → 진입 시 `ENTER`, 이탈 시 `EXIT` ✅ |
| `LocationData` | lat, lon, alt, heading(항상 0), confidence(5.0), timestamp |

`FUSED_LOCATION`은 dangerous 권한이라 런타임 요청(또는 `pm grant`)이 필요하다.

## 7. LLM SDK (가이드 미공개, Maven `ai.pleos.playground:LLM:2.1.3.2`)

- API: `LLM(context, Mode.ON_DEVICE|SERVER)`, `generateContent`, `generateContentStream`, `startChat`, `sendMessage(Stream)`, `setModelParameter(TEMPERATURE|MAX_TOKENS|MODEL_NAME)`, `registerApp(clientId, secret)`. 권한 `pleos.car.permission.LLM_SERVICE`(에뮬레이터에 정의돼 있음)
- ON_DEVICE: `initialize` OK, 이후 `generateContentStream`/`startChat`/`sendMessageStream`은 **"Model is not running! Please retry after running"**, `generateContent`/`sendMessage`는 **콜백 자체가 오지 않음** (60초 타임아웃)
- SERVER (앱 등록 없음): `initialize` 후 `generateContent`가 8분 넘게 무응답
- → 현재 에뮬레이터 이미지에서는 쓸 수 없다. 공모전용으로 공개되면 다시 확인할 것.

---

## 8. 테스트하지 못한 것 / 다음 단계

| 항목 | 이유 | 하려면 |
|---|---|---|
| STT/TTS/LLM 서버 모드 | My Project Client ID/Secret 필요 | Pleos Playground ▸ My Project에서 발급 → `registerApp()` |
| STT 마이크 경로 | 에뮬레이터 마이크 토글이 GUI 전용 | Extended Controls에서 켠 뒤 `stt:ON_DEVICE:mic` |
| `requestNavigationScreen`, `requestTrip`, `requestRouteWithWaypoints` | 시간 관계상 | NaviSuite에 추가 |
| Vehicle `move*`(시트 이동), `selectSeatMemory`, 조명 스위치 일부 set | set은 대표 API만 | `setRoundTrips()`에 추가 |
| 주행 중 제한(`ERROR_DRIVING_RESTRICTED`) | 미재현 | 콘솔에서 기어 D + 속도 올린 뒤 제어 API 호출 |

## 9. 공모전 개발 팁 (이번 테스트로 얻은 것)

- 첫 화면에서 `check…Capabilities`를 돌려 **지원 여부와 영역을 받아 두고**, 그 영역으로 get/set/register 하라. 단일 좌석을 하드코딩하면 실패한다.
- 개발할 때는 **Virtual Console 탭을 항상 열어 둘 것** (안 열면 set이 반영 안 되는데 에러도 없다).
- `onFailed`는 `Exception` 메시지 기준으로 처리하고, VehicleException 분기는 보조로만.
- 데모 시나리오 자동화에는 위 Virtual Console HTTP API가 유용하다 (PT 시연 리허설에 그대로 쓸 수 있음).
- 출품 앱 매니페스트에는 **문서에 있는 퍼미션만** 선언하라. (테스트 앱의 `PRIVATE_CAR_*`는 실험용)

---

## 부록 A. Vehicle get/check 전체 결과 (Virtual Console 활성 + PRIVATE_* 권한)

✅ 성공 · ⚠️ PRIVATE_* 권한이 있어야 성공 · ❌ 실패. "동작 영역"은 성공한 영역 목록.

#### Brake (2/2 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkBrakePedalStatusCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `getCurrentBrakePedalStatus` |  | false |

#### CarInfo (5/5 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkCRNInfoCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `checkVINInfoCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `getCRNInfo` |  | "invalid" |
| ⚠️ | `getCapabilityInfo` |  | (PRIVATE_* 권한 필요) {LANE_CHANGE_ASSIST_ENABLED=CapabilityConfig(areaType=0, access=3, changeMode=1, configArray=[], configString=, maxSampleRate=0.0, minSample |
| ✅ | `getVINInfo` |  | "invalid" |

#### Display (2/2 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkTemperatureDisplayUnitCapabilities` |  | CapabilityConfig(accessType=READ_WRITE) |
| ✅ | `getCurrentTemperatureDisplayUnit` |  | CELSIUS |

#### Door (6/6 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkDoorLockCapabilities` |  | [DoorArea[HOOD], DoorArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT]] |
| ✅ | `checkDoorPositionCapabilities` |  | {DoorArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=1), DoorArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=1), DoorArea[REAR]=AreaBound |
| ✅ | `checkGloveBoxLockCapabilities` |  | CapabilityConfig(accessType=READ_WRITE) |
| ✅ | `getCurrentDoorPosition` | DoorArea[ROW_1_LEFT] ; DoorArea[ROW_1_RIGHT] ; DoorArea[ROW_2_LEFT] ; DoorArea[ROW_2_RIGHT… | 0 |
| ✅ | `getCurrentGloveBoxLockState` |  | false |
| ✅ | `getDoorLockState` | DoorArea[HOOD] ; DoorArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT] | true |

#### DrivingMode (4/6 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkCruiseControlStateCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `checkCruiseControlTargetSpeedCapabilities` |  | CapabilityConfig(accessType=READ) |
| ❌ | `checkDrivingModeStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `getCruiseControlState` |  | ENABLED |
| ✅ | `getCruiseControlTargetSpeed` |  | 15.0 |
| ❌ | `getCurrentDrivingModeState` |  | VHAL 미정의 프로퍼티(0x21400410) |

#### EvBattery (11/19 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ❌ | `checkCumulativeEvEfficiencyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkCurrentEvEfficiencyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkEvBatteryStateOfChargeCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `checkEvChargeCurrentDrawLimitCapabilities` |  | [48.0] |
| ✅ | `checkEvChargePercentLimitCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [50.0, 55.0, 60.0, 65.0, 70.0, 75.0, 80.0, 85.0, 90.0, 95.0, 100.0] |
| ✅ | `checkEvChargePortOpenCapabilities` |  | CapabilityConfig(accessType=READ_WRITE) |
| ✅ | `checkEvChargeSwitchCapabilities` |  | CapabilityConfig(accessType=READ_WRITE) |
| ❌ | `checkInstantaneousEvEfficiencyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkSinceChargeEvEfficiencyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getCumulativeEvEfficiency` |  | VHAL 미정의 프로퍼티(0x21600b15) |
| ❌ | `getCurrentEvEfficiency` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `getEvBatteryCapacity` |  | 77400.0 |
| ✅ | `getEvBatteryLevel` |  | 60000.0 |
| ✅ | `getEvBatteryStateOfCharge` |  | 77 |
| ✅ | `getEvChargeCurrentDrawLimit` |  | 12.5 |
| ✅ | `getEvChargeSwitchState` |  | false |
| ❌ | `getInstantaneousEvEfficiency` |  | VHAL 미정의 프로퍼티(0x21600b13) |
| ❌ | `getSinceChargeEvEfficiency` |  | VHAL 미정의 프로퍼티(0x21600b14) |
| ✅ | `isEvChargePortOpen` |  | false |

#### Fuel (0/10 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ❌ | `checkCumulativeFuelEconomyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkCurrentFuelEconomyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkFuelPercentCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkInstantaneousFuelEconomyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkSinceChargeFuelEconomyCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getCumulativeFuelEconomy` |  | VHAL 미정의 프로퍼티(0x21600be2) |
| ❌ | `getCurrentFuelEconomy` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getFuelPercent` |  | VHAL 미정의 프로퍼티(0x21400be5) |
| ❌ | `getInstantaneousFuelEconomy` |  | VHAL 미정의 프로퍼티(0x21600be0) |
| ❌ | `getSinceChargeFuelEconomy` |  | VHAL 미정의 프로퍼티(0x21600be1) |

#### Gear (1/1 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `getCurrentGearSelectionState` |  | GEAR_PARK |

#### HVAC (27/29 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkHvacAcCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacAirCleanerCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacAutoCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT]] |
| ✅ | `checkHvacAutoRecirculationCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacDefrosterCapabilities` |  | [WindowArea[FRONT_WINDSHIELD], WindowArea[REAR_WINDSHIELD]] |
| ✅ | `checkHvacDualCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT]] |
| ✅ | `checkHvacFanDirectionCapabilities` |  | {SeatArea[ROW_1_LEFT, ROW_1_RIGHT]=[]} |
| ✅ | `checkHvacFanSpeedCapabilities` |  | {SeatArea[ROW_1_LEFT, ROW_1_RIGHT]=AreaBounds(minValue=1, maxValue=8)} |
| ❌ | `checkHvacMaxAcCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkHvacMaxDefrostCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacPowerCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacRecirculationCapabilities` |  | [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkHvacTemperatureCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=15.0, maxValue=30.0), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=15.0, maxValue=30.0)} |
| ✅ | `checkHvacTemperatureCurrentCapabilities` |  | CapabilityConfig(accessType=READ), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT], SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, |
| ✅ | `getHvacAcState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | true |
| ✅ | `getHvacAirCleanerState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | false |
| ✅ | `getHvacAutoRecirculationState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | false |
| ✅ | `getHvacAutoState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT] | false |
| ✅ | `getHvacDefrosterState` | WindowArea[FRONT_WINDSHIELD] ; WindowArea[REAR_WINDSHIELD] | false |
| ✅ | `getHvacDualState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT] | false |
| ✅ | `getHvacFanDirection` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT] | FACE |
| ✅ | `getHvacFanSpeed` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT] | 2 |
| ❌ | `getHvacMaxAcState` |  | VHAL 미정의 프로퍼티(HVAC_MAX_AC_ON) |
| ✅ | `getHvacMaxDefrostState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | false |
| ✅ | `getHvacPowerState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | true |
| ✅ | `getHvacRecirculationState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | false |
| ✅ | `getHvacTemperature` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 27.0 |
| ✅ | `getHvacTemperatureCurrent` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEF… | 19.1 |
| ✅ | `getHvacTemperatureCurrent` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEF… | 19.1 |

#### Light (28/38 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ❌ | `checkAmbientColorAutoByDriveModeEnabledCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkAmbientLightBrightnessCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]=AreaBounds(minValue=0, ma |
| ✅ | `checkAmbientLightBrightnessColorCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]] |
| ✅ | `checkAmbientLightStateCapabilities` |  | CapabilityConfig(accessType=READ), {SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]=[ON, OFF]} |
| ✅ | `checkAmbientLightSwitchCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT]=[AUTOMATIC, ON, OFF]} |
| ✅ | `checkCabinLightStateCapabilities` |  | CapabilityConfig(accessType=READ), [OFF, ON] |
| ✅ | `checkCabinLightSwitchCapabilities` |  | [OFF, ON] |
| ❌ | `checkFrontFogLightStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkFrontFogLightSwitchCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkHighBeamLightStateCapabilities` |  | CapabilityConfig(accessType=READ), [OFF, ON] |
| ✅ | `checkHighBeamLightSwitchCapabilities` |  | [OFF, ON] |
| ✅ | `checkLowBeamLightStateCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [OFF, ON] |
| ✅ | `checkLowBeamLightSwitchCapabilities` |  | [OFF, ON] |
| ✅ | `checkReadingLightStateCapabilities` |  | CapabilityConfig(accessType=READ), {SeatArea[ROW_2_LEFT]=[OFF, ON], SeatArea[ROW_2_RIGHT]=[OFF, ON], SeatArea[ROW_1_LEFT]=[OFF, ON], SeatAre |
| ✅ | `checkReadingLightSwitchCapabilities` |  | {SeatArea[ROW_2_LEFT]=[OFF, ON], SeatArea[ROW_2_RIGHT]=[OFF, ON], SeatArea[ROW_1_LEFT]=[OFF, ON], SeatArea[ROW_1_RIGHT]=[OFF, ON]} |
| ❌ | `checkRearFogLightStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkRearFogLightSwitchCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkTailLampLightStateCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [OFF, ON] |
| ✅ | `checkTailLampLightSwitchCapabilities` |  | [OFF, ON] |
| ❌ | `getAmbientColorAutoByDriveModeEnabled` |  | VHAL 미정의 프로퍼티(0x21200787) |
| ✅ | `getAmbientLightBrightness` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | 90 |
| ✅ | `getAmbientLightBrightnessColor` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | 2718207 |
| ✅ | `getAmbientLightState` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | OFF |
| ✅ | `getAmbientLightSwitch` | SeatArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT] | OFF |
| ✅ | `getCabinLightSwitch` |  | OFF |
| ✅ | `getCurrentCabinLightState` |  | OFF |
| ❌ | `getCurrentFrontFogLightState` |  | VHAL 미정의 프로퍼티(FRONT_FOG_LIGHTS_STATE) |
| ✅ | `getCurrentHighBeamLightState` |  | OFF |
| ✅ | `getCurrentLowBeamLightState` |  | OFF |
| ✅ | `getCurrentReadingLightState` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | OFF |
| ❌ | `getCurrentRearFogLightState` |  | VHAL 미정의 프로퍼티(REAR_FOG_LIGHTS_STATE) |
| ✅ | `getCurrentTailLampLightState` |  | OFF |
| ❌ | `getFrontFogLightSwitch` |  | VHAL 미정의 프로퍼티(FRONT_FOG_LIGHTS_SWITCH) |
| ✅ | `getHighBeamLightSwitch` |  | OFF |
| ✅ | `getLowBeamLightSwitch` |  | OFF |
| ✅ | `getReadingLightSwitch` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | OFF |
| ❌ | `getRearFogLightSwitch` |  | VHAL 미정의 프로퍼티(REAR_FOG_LIGHTS_SWITCH) |
| ✅ | `getTailLampLightSwitch` |  | OFF |

#### Odometer (3/19 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkCurrentDistanceDrivenCapabilities` |  | CapabilityConfig(accessType=READ) |
| ❌ | `checkTripCumulativeDrivingDistanceCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripCumulativeDrivingTimeCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripCurrentDrivingDistanceCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripCurrentDrivingTimeCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripDrivingDistanceCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripDrivingTimeCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripInstantaneousDrivingDistanceCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripSinceRefillDrivingDistanceCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkTripSinceRefillDrivingTimeCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `getCurrentDistanceDriven` |  | 1234.0 |
| ✅ | `getRangeRemainingDistance` |  | 99000.0 |
| ❌ | `getTripCumulativeDrivingDistance` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripCumulativeDrivingTime` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripCurrentDrivingDistance` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripCurrentDrivingTime` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripInstantaneousDrivingDistance` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripSinceRefillDrivingDistance` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getTripSinceRefillDrivingTime` |  | ERROR_NOT_SUPPORTED_PROPERTY |

#### Safety (7/19 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkAirbagCapabilities` |  | [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ❌ | `checkAirbagDeployedCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkBrakeFluidLevelStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkEngineOilLevelStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkLowWasherFluidWarningStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkPowerDownWarningIndicatorCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `checkSeatBuckledCapabilities` |  | [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_2_CENTER], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ❌ | `checkSeatNotBuckledWarningCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ⚠️ | `checkTelltalesCapabilities` |  | (PRIVATE_* 권한 필요) CapabilityConfig(accessType=READ) |
| ❌ | `checkTransmissionOilLevelStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getBrakeFluidLevelState` |  | VHAL 미정의 프로퍼티(0x21400730) |
| ❌ | `getEngineOilLevelState` |  | VHAL 미정의 프로퍼티(ENGINE_OIL_LEVEL) |
| ⚠️ | `getTelltalesInfo` |  | (PRIVATE_* 권한 필요) Telltales(telltales=[]) |
| ❌ | `getTransmissionOilLevelState` |  | VHAL 미정의 프로퍼티(0x21400731) |
| ❌ | `hasLowWasherFluidWarning` |  | VHAL 미정의 프로퍼티(0x2120071e) |
| ✅ | `hasPowerDownWarningIndicator` |  | false |
| ❌ | `hasSeatAirbagBeenDeployed` |  | VHAL 미정의 프로퍼티(0x2520071f) |
| ✅ | `hasSeatAirbagEnabled` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | false |
| ❌ | `hasSeatBeltNotBuckledWarning` |  | VHAL 미정의 프로퍼티(0x25200709) |

#### Seat (53/53 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkMoveSeatBackrestAngleCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatBeltHeightCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatDepthCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatForeAftCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatHeadrestAngleCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatHeadrestForeAftCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatHeadrestHeightCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatHeightCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatLumbarForeAftCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatLumbarSideSupportCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkMoveSeatTiltCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkSeatBackrestAngleCapabilities` |  | {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_1_LEFT] |
| ✅ | `checkSeatBackrestAnglePositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minVal |
| ✅ | `checkSeatBeltBuckledCapabilities` |  | [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_2_CENTER], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkSeatBeltHeightCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=0, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=0, maxValue=10)} |
| ✅ | `checkSeatBeltHeightPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=0, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValu |
| ✅ | `checkSeatDepthCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatDepthPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatForeAftCapabilities` |  | {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_1_LEFT] |
| ✅ | `checkSeatForeAftPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minVal |
| ✅ | `checkSeatHeadrestAngleCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatHeadrestAnglePositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatHeadrestForeAftCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatHeadrestForeAftPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatHeadrestHeightCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatHeadrestHeightPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatHeightCapabilities` |  | {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_1_LEFT] |
| ✅ | `checkSeatHeightPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minVal |
| ✅ | `checkSeatLumbarForeAftCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatLumbarForeAftPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatLumbarSideSupportCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=-10, maxValue=10)} |
| ✅ | `checkSeatLumbarSideSupportPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=-10, maxValue=10), SeatArea[ROW_1_RIGHT]=AreaBounds(minVa |
| ✅ | `checkSeatMemoryCapabilities` |  | CapabilityConfig(accessType=WRITE), {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=1, maxValue=2)} |
| ✅ | `checkSeatOccupancyCapabilities` |  | [SeatArea[ROW_2_LEFT], SeatArea[ROW_2_RIGHT], SeatArea[ROW_2_CENTER], SeatArea[ROW_1_LEFT], SeatArea[ROW_1_RIGHT]] |
| ✅ | `checkSeatTemperatureCapabilities` |  | {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=3), SeatArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=3), SeatArea[ROW_1_LEFT]=Are |
| ✅ | `checkSeatTiltCapabilities` |  | {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_1_LEFT] |
| ✅ | `checkSeatTiltPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {SeatArea[ROW_2_LEFT]=AreaBounds(minValue=0, maxValue=100), SeatArea[ROW_2_RIGHT]=AreaBounds(minVal |
| ✅ | `checkSeatVentilationCapabilities` |  | {SeatArea[ROW_1_LEFT]=AreaBounds(minValue=0, maxValue=3), SeatArea[ROW_1_RIGHT]=AreaBounds(minValue=0, maxValue=3)} |
| ✅ | `getCurrentSeatBackrestAnglePosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | 0 |
| ✅ | `getCurrentSeatBeltBuckledState` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_CENTE… | false |
| ✅ | `getCurrentSeatBeltHeightPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 10 |
| ✅ | `getCurrentSeatDepthPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatForeAftPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | 0 |
| ✅ | `getCurrentSeatHeadrestAnglePosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatHeadrestForeAftPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatHeadrestHeightAftPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatHeightPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | 0 |
| ✅ | `getCurrentSeatLumbarForeAftPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatLumbarSideSupportPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 0 |
| ✅ | `getCurrentSeatOccupancy` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_CENTE… | true |
| ✅ | `getCurrentSeatTiltPosition` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | 0 |
| ✅ | `getHvacSeatTemperature` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] ; SeatArea[ROW_2_LEFT] ; SeatArea[ROW_2_RIGHT… | 2 |
| ✅ | `getHvacSeatVentilationState` | SeatArea[ROW_1_LEFT] ; SeatArea[ROW_1_RIGHT] | 1 |

#### SideMirror (2/2 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkHvacSideMirrorHeatCapabilities` |  | {MirrorArea[DRIVER_LEFT]=AreaBounds(minValue=0, maxValue=3), MirrorArea[DRIVER_RIGHT]=AreaBounds(minValue=0, maxValue=3), MirrorArea[DRIVER_ |
| ✅ | `getHvacSideMirrorHeatState` | MirrorArea[DRIVER_LEFT] ; MirrorArea[DRIVER_RIGHT] ; MirrorArea[DRIVER_LEFT, DRIVER_RIGHT] | 0 |

#### SteeringWheel (4/4 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkHvacSteeringWheelHeatCapabilities` |  | AreaBounds(minValue=0, maxValue=3) |
| ✅ | `checkSteeringWheelAngleCapabilities` |  | CapabilityConfig(accessType=READ), AreaBounds(minValue=-470.0, maxValue=500.0) |
| ✅ | `getCurrentSteeringWheelAngle` |  | 45.0 |
| ✅ | `getHvacSteeringWheelHeatState` |  | 1 |

#### Tire (3/5 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkLowTirePressureCapabilities` |  | CapabilityConfig(accessType=READ), [WheelArea[UNKNOWN, LEFT_FRONT], WheelArea[UNKNOWN, RIGHT_FRONT], WheelArea[UNKNOWN, LEFT_REAR], WheelAre |
| ❌ | `checkTirePressureCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkWheelAreaCapabilities` |  | [WheelArea[UNKNOWN, LEFT_FRONT], WheelArea[UNKNOWN, RIGHT_FRONT], WheelArea[UNKNOWN, LEFT_REAR], WheelArea[UNKNOWN, RIGHT_REAR]] |
| ✅ | `getLowTirePressure` | WheelArea[LEFT_FRONT] ; WheelArea[RIGHT_FRONT] ; WheelArea[LEFT_REAR] ; WheelArea[RIGHT_RE… | 137.0 |
| ❌ | `getTirePressure` |  | VHAL 미정의 프로퍼티(TIRE_PRESSURE) |

#### Toll (0/2 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ❌ | `checkEtcsVoiceGuidanceVolumeCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `getCurrentEtcsVoiceGuidanceVolume` |  | VHAL 미정의 프로퍼티(0x21400d01) |

#### TurnSignal (2/2 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkTurnSignalStateCapabilities` |  | CapabilityConfig(accessType=READ) |
| ✅ | `getCurrentTurnSignalState` |  | OFF |

#### Window (7/13 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkMoveWindowBlindCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), [WindowArea[ROW_1_LEFT], WindowArea[ROW_1_RIGHT], WindowArea[ROW_2_LEFT], WindowArea[ROW_2_RIGHT]] |
| ❌ | `checkSmartGlassLevelCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkSmartGlassStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ❌ | `checkSmartGlassZoneStateCapabilities` |  | ERROR_NOT_SUPPORTED_PROPERTY |
| ✅ | `checkWindowBlindPositionCapabilities` |  | CapabilityConfig(accessType=READ_WRITE), {WindowArea[ROW_1_LEFT]=AreaBounds(minValue=0, maxValue=10), WindowArea[ROW_1_RIGHT]=AreaBounds(min |
| ✅ | `checkWindowLockCapabilities` |  | [WindowArea[ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT], WindowArea[ROW_2_LEFT], WindowArea[ROW_2_RIGHT]] |
| ✅ | `checkWindowPositionCapabilities` |  | {WindowArea[ROW_1_LEFT]=AreaBounds(minValue=0, maxValue=10), WindowArea[ROW_1_RIGHT]=AreaBounds(minValue=0, maxValue=10), WindowArea[ROW_2_L |
| ❌ | `getCurrentSmartGlassLevel` |  | VHAL 미정의 프로퍼티(0x23400535) |
| ❌ | `getCurrentSmartGlassState` |  | VHAL 미정의 프로퍼티(0x21200534) |
| ✅ | `getCurrentWindowBlindPosition` | WindowArea[ROW_1_LEFT] ; WindowArea[ROW_1_RIGHT] ; WindowArea[ROW_2_LEFT] ; WindowArea[ROW… | 0 |
| ✅ | `getCurrentWindowLockState` | WindowArea[ROW_2_LEFT] ; WindowArea[ROW_2_RIGHT] ; WindowArea[ROW_1_LEFT, ROW_1_RIGHT, ROW… | false |
| ✅ | `getCurrentWindowPosition` | WindowArea[ROW_1_LEFT] ; WindowArea[ROW_1_RIGHT] ; WindowArea[ROW_2_LEFT] ; WindowArea[ROW… | 0 |
| ❌ | `getSmartGlassConfig` |  | VHAL 미정의 프로퍼티(0x23411009) / java.lang.IllegalArgumentException: Exactly one window area is required |

#### Wiper (4/4 성공)

| 결과 | API | 동작 영역 | 반환값 / 실패 사유 |
|---|---|---|---|
| ✅ | `checkWiperStateCapabilities` |  | {WindowArea[FRONT_WINDSHIELD]=[OFF, ON, SERVICE], WindowArea[REAR_WINDSHIELD]=[OFF, ON, SERVICE]} |
| ✅ | `checkWiperSwitchCapabilities` |  | {WindowArea[FRONT_WINDSHIELD]=[OFF, CONTINUOUS_LEVEL_1, CONTINUOUS_LEVEL_3, AUTO], WindowArea[REAR_WINDSHIELD]=[OFF, CONTINUOUS_LEVEL_1, CON |
| ✅ | `getCurrentWiperState` | WindowArea[FRONT_WINDSHIELD] ; WindowArea[REAR_WINDSHIELD] | OFF |
| ✅ | `getCurrentWiperSwitch` | WindowArea[FRONT_WINDSHIELD] ; WindowArea[REAR_WINDSHIELD] | OFF |

## 부록 B. 소스

- 참가자 가이드 PDF (2026.10.28 설명회 정리)
- Pleos 개발자 문서: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- 에뮬레이터 설치: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Virtual Console: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
- AAR 바이트코드 분석 (javap) — 문서와 다른 시그니처는 모두 AAR 기준
