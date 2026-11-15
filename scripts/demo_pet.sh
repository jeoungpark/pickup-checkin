#!/usr/bin/env bash
# 픽업 체크인 — 반려견 유치원 하원 드랍 데모 (같은 엔진, 플랜만 pet)
#  실제 기사↔견주 문자 장면을 차가 대신 보낸다:
#   "5분 후 도착합니다" / "아주 천천히 내려오세요 토리가 마지막이에요" / "오늘 토리가 마지막이라 좀 늦을 거 같아요~"
#   "저 도착했는데 문자를 안 보내드렸네요" → 도착하면 자동으로 감
# 사용: python3 relay/server.py &   ./scripts/demo_pet.sh [adb-serial] [pause]
set -euo pipefail
SERIAL="${1:-emulator-5554}"; PAUSE="${2:-4}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
PORT=28088; C="http://127.0.0.1:$PORT"; PKG=com.umosone.pickupcheckin; RELAY=http://127.0.0.1:8787

say()  { printf '\n\033[1;35m▶ %s\033[0m\n' "$*"; }
vc()   { curl -s -m 5 -X PUT "$C$1" -o /dev/null -w "  console $1 → %{http_code}\n"; }
geo()  { $ADB emu geo fix "$2" "$1" >/dev/null; echo "  GPS → $1, $2"; }
demo() { $ADB shell am start-foreground-service -n $PKG/.car.PickupService --es demo "$1" >/dev/null 2>&1; echo "  DEMO 주입: $1"; }
reply(){ curl -s -m 3 -X POST "$RELAY/api/reply" -H 'Content-Type: application/json' -d "{\"rider\":\"$1\",\"code\":\"$2\"}" >/dev/null || true; echo "  보호자 웹: $1 → $2"; }
wait_(){ sleep "${1:-$PAUSE}"; }

# pet.json 좌표 (학원 데모와 같은 좌표를 재사용)
START=(37.5150 127.0400)
KONGI=(37.4945 127.0617); KONGI_NEAR=(37.4995 127.0560)
TORI=(37.4880 127.0440);  TORI_FAR=(37.4985 127.0580); TORI_NEAR=(37.4900 127.0480)
DAYCARE=(37.5050 127.0560); DAYCARE_NEAR=(37.5000 127.0520)

say "준비"
$ADB forward tcp:$PORT tcp:8088 >/dev/null
$ADB reverse tcp:8787 tcp:8787 >/dev/null   # 앱 → PC 중계 서버 (에뮬레이터 네트워크와 무관)
( while true; do curl -s -m 2 -X POST "$C/browser/session?client=pet-demo" >/dev/null || true; sleep 1; done ) &
HB=$!; trap 'kill $HB 2>/dev/null || true' EXIT
sleep 2; vc "/vehicle/gear?gear=Park"; for d in fl fr rl rr; do vc "/vehicle/body?door_$d=false"; done; vc "/vehicle/body?trunk=false"
geo "${START[@]}"

say "1. 하원 드랍 시작 (콩이 → 토리 마지막 → 유치원)"
$ADB shell am force-stop $PKG
$ADB shell am start -n $PKG/.ui.MainActivity --es plan pet --ez autostart true >/dev/null; wait_ 8

say "2. 출발 → 콩이 '이제 콩이 차례', 토리 '앞에 1곳 남았어요. 토리가 마지막이에요. 천천히 나오세요'"
vc "/vehicle/gear?gear=Drive"; wait_

say "3. 콩이네 접근·도착 → '3분 뒤 도착', '도착했어요. 기사님이 콩이와 기다리고 있어요' (문자 깜빡할 일 없음)"
geo "${KONGI_NEAR[@]}"; wait_; geo "${KONGI[@]}"; wait_ 2; vc "/vehicle/gear?gear=Park"; wait_

say "4. 콩이 보호자가 웹에서 '받았어요' → 바로 인계 확정 '콩이 내려드렸어요 (시각)'"
vc "/vehicle/body?door_rl=true"; wait_ 2; vc "/vehicle/body?door_rl=false"; wait_ 2
reply kongi received; wait_

say "5. 출발 → 토리 '이제 토리 차례예요' · 토리 보호자 '넵' (운전자에게는 읽어주지 않음)"
vc "/vehicle/gear?gear=Drive"; wait_ 2; geo "${TORI_FAR[@]}"; reply tori ok; wait_

say "6. 정체로 10분 지연 (DEMO) → 토리 보호자 '… 10분 늦어요. 토리와 같이 가고 있으니 천천히 준비하세요' → '천천히 오세요'"
demo "delay:10"; wait_; reply tori slow; wait_

say "7. 토리네 접근·도착 → 정차, 보호자 아직 → 대기 3분째 (DEMO 시간 앞당김)"
geo "${TORI_NEAR[@]}"; wait_; geo "${TORI[@]}"; wait_ 2; vc "/vehicle/gear?gear=Park"; wait_ 2
demo "wait:3"; wait_

say "8. 트렁크에서 켄넬 꺼내 인계 → 출발 → '토리 내려드렸어요', '모두 내려드렸어요'"
vc "/vehicle/body?trunk=true"; wait_ 2; vc "/vehicle/body?trunk=false"; wait_ 2; vc "/vehicle/gear?gear=Drive"; wait_

say "9. 유치원 복귀 (지오펜스)"
geo "${DAYCARE_NEAR[@]}"; wait_ 2; geo "${DAYCARE[@]}"; wait_; vc "/vehicle/gear?gear=Park"; wait_ 2
say "끝"
