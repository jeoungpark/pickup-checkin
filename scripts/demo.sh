#!/usr/bin/env bash
# 픽업 체크인 엔드투엔드 데모 (학원 픽업 시나리오)
#
#  실제 신호: 기어·문 = 가상 콘솔 HTTP API, 지점 접근/도착 = adb emu geo fix → FusedLocation 지오펜스
#  주입 신호: 차막힘 지연 (에뮬레이터 내비가 목업이라 정체 변화가 없음) → 앱의 DEMO 명령
#
# 사용: relay 서버를 먼저 띄운 뒤   ./scripts/demo.sh [adb-serial] [step-pause-seconds]
#   python3 relay/server.py &       # http://localhost:8787 에서 보호자 화면 확인
set -euo pipefail

SERIAL="${1:-emulator-5554}"
PAUSE="${2:-4}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
PORT=28088                       # 가상 콘솔 포워딩 (다른 도구의 8088과 겹치지 않게)
C="http://127.0.0.1:$PORT"
PKG=com.umosone.pickupcheckin
RELAY=http://127.0.0.1:8787

say()  { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
vc()   { curl -s -m 5 -X PUT "$C$1" -o /dev/null -w "  console $1 → %{http_code}\n"; }
geo()  { $ADB emu geo fix "$2" "$1" >/dev/null; echo "  GPS → $1, $2"; }   # geo fix 는 경도 위도 순서
demo() { $ADB shell am start-foreground-service -n $PKG/.car.PickupService --es demo "$1" >/dev/null 2>&1; echo "  DEMO 주입: $1"; }
wait_() { sleep "${1:-$PAUSE}"; }

# 좌표 (assets/plans/academy.json 과 동일)
START=(37.5150 127.0400)
MATH=(37.4945 127.0617);  MATH_NEAR=(37.4995 127.0560)    # 학원 약 700m 전 (접근 1.2km 안)
PIANO=(37.4880 127.0440); PIANO_FAR=(37.4985 127.0580)    # 도곡동 약 1.7km 밖 (접근원 1.2km 바깥)
PIANO_NEAR=(37.4900 127.0480)
HOME_=(37.5050 127.0560); HOME_NEAR=(37.5000 127.0520)

say "준비: 가상 콘솔 포워딩 + 세션 하트비트"
$ADB forward tcp:$PORT tcp:8088 >/dev/null
$ADB reverse tcp:8787 tcp:8787 >/dev/null   # 앱 → PC 중계 서버 (에뮬레이터 네트워크와 무관)
( while true; do curl -s -m 2 -X POST "$C/browser/session?client=pickup-demo" >/dev/null || true; sleep 1; done ) &
HB=$!; trap 'kill $HB 2>/dev/null || true' EXIT
sleep 2
vc "/vehicle/gear?gear=Park"
for d in fl fr rl rr; do vc "/vehicle/body?door_$d=false"; done
geo "${START[@]}"
curl -s -m 2 "$RELAY/api/inbox" >/dev/null && echo "  relay OK ($RELAY)" || echo "  ⚠ relay 서버가 꺼져 있음 (보호자 화면 없이 진행)"

say "1. 앱 실행 → 주차 중 '오늘 픽업 시작' (지우 → 하윤 → 집)"
$ADB shell am force-stop $PKG
$ADB shell am start -n $PKG/.ui.MainActivity --es plan academy --ez autostart true >/dev/null
wait_ 8

say "2. 기어 D 출발 → 지우 보호자에게 '출발, 다음 차례'"
vc "/vehicle/gear?gear=Drive"; wait_

say "3. 수학학원 접근 (지오펜스 1.2km 진입) → 지우에게 '곧 도착'"
geo "${MATH_NEAR[@]}"; wait_

say "4. 수학학원 도착 (지오펜스 150m) → 정차 P"
geo "${MATH[@]}"; wait_ 2
vc "/vehicle/gear?gear=Park"; wait_

say "5. 지우가 뒷좌석 왼쪽 문을 열고 타서 닫음 → 후보"
vc "/vehicle/body?door_rl=true"; wait_ 2
vc "/vehicle/body?door_rl=false"; wait_

say "6. 출발(D) → 지우 탑승 확정 '지우 탔어요' + 하윤에게 '다음 차례'"
vc "/vehicle/gear?gear=Drive"; wait_
geo "${PIANO_FAR[@]}"; wait_

say "7. 차막힘 발생 (DEMO 주입: 기준보다 8분 늦어짐) → 하윤 엄마에게 '8분 늦어요'"
demo "delay:8"; wait_

say "8. 하윤이 웹에서 '5분 늦어요' → 운전자 음성"
curl -s -m 3 -X POST "$RELAY/api/late" -H 'Content-Type: application/json' -d '{"rider":"hayun","minutes":5}' >/dev/null || true
wait_

say "9. 피아노학원 접근·도착 → 정차 (하윤은 아직 안 나옴)"
geo "${PIANO_NEAR[@]}"; wait_
geo "${PIANO[@]}"; wait_ 2
vc "/vehicle/gear?gear=Park"; wait_

say "10. 대기 경험: 실내 온도·무드등 준비, 3분째 → 하윤에게 '차가 기다려요 · 3분째' (DEMO: 대기 시간 앞당김)"
demo "wait:3"; wait_

say "11. 하윤이 뒷좌석 오른쪽 문으로 탑승 → 출발 → '하윤 탔어요', '모두 탔어요'"
vc "/vehicle/body?door_rr=true"; wait_ 2
vc "/vehicle/body?door_rr=false"; wait_ 2
vc "/vehicle/gear?gear=Drive"; wait_

say "12. 집 도착 (지오펜스) → 전원에게 '도착했어요'"
geo "${HOME_NEAR[@]}"; wait_ 2
geo "${HOME_[@]}"; wait_
vc "/vehicle/gear?gear=Park"; wait_ 2

say "끝. 로그: $ADB logcat -d -s PICKUP"
