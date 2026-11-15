package com.umosone.pickupcheckin.core

/**
 * 픽업 체크인 상태 머신.
 *
 * 입력: 기어, 문, 착좌/벨트 변화, "다음 경유지 접근/도착" 신호(내비·지오펜스·데모 주입 어느 쪽이든),
 *       탑승자 메시지("늦어요"), 운전자 확인(주차 중에만).
 * 출력: [Effect] — 음성, 탑승자 알림, 경로 설정, 확인 질문.
 *
 * 탑승 판정 규칙 (에뮬레이터 실측 결과를 반영):
 *  1. 정차(P) 중 픽업 지점에서 조수석/뒷문이 열렸다 닫히면 → CANDIDATE (아직 알리지 않음)
 *  2. 이후 기어 D로 출발하면 → BOARDED 확정 (근거: DOOR_CYCLE_THEN_DRIVE)
 *  3. 착좌·벨트 값이 "바뀌면"(방향은 보지 않음) 즉시 BOARDED (근거: SEAT_OR_BELT_CHANGE)
 *     — 착좌 센서가 없거나 값이 뒤집혀 들어오는 차량에서도 오판정하지 않도록 변화량만 쓴다.
 *  4. 문 사이클 없이 출발하면 → UNCONFIRMED, 다음 정차(P) 때 운전자에게 한 번 묻는다.
 *  5. 주행 중(P가 아닐 때) 문 이벤트와 운전석 문은 무시한다.
 *  6. 한 지점에 여러 명이고 보호자가 서로 다르면(또는 보호자 정보가 없으면) 문·좌석 신호로 누구인지
 *     알 수 없으므로 자동 확정하지 않고 UNCONFIRMED → 다음 P에서 확인한다. 보호자가 같으면(형제) 함께 확정.
 *
 * 도착 판정: 지점 근처(지오펜스·내비 경유지) **그리고** 기어 P일 때만 "도착·대기"로 본다.
 *  근처에 들어온 것만으로는 도착을 선언하지 않는다(신호 대기·지나가는 경우 오알림 방지). 순서는 무관.
 *
 * 경유지 2곳 제한: 남은 픽업 지점 중 다음 2곳만 경유지로 설정하고, 그 2곳을 지나면 다시 설정한다(롤링).
 *
 * 지연(차막힘) 자동 연락:
 *  - 다음 지점으로 출발한 뒤 처음 받은 도착 예정 시각(ETA)을 기준으로 삼는다.
 *  - 새 ETA가 기준보다 LATE_SECONDS(5분) 이상 늦어지면 그 지점 보호자에게 "N분 늦어요"를 보낸다.
 *  - 이미 알린 지연보다 RENOTIFY_SECONDS(3분) 이상 더 늦어질 때만 다시 알린다 (알림 폭탄 방지).
 *  - 곧 도착(3분 전) 이후에는 지연 알림을 보내지 않는다.
 */
class PickupEngine(
    private val plan: Plan,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val emit: (Effect) -> Unit,
) {
    var phase = Phase.PLANNED; private set
    var gear = Gear.UNKNOWN; private set
    var currentStop = 0; private set

    private val status = linkedMapOf<String, RiderStatus>().apply {
        plan.stops.forEach { s -> s.riders.forEach { put(it.id, if (s.type == StopType.DROPOFF) RiderStatus.ON_BOARD else RiderStatus.WAITING) } }
    }
    private var windowStart = 0
    private val soonSent = mutableSetOf<Int>()
    private val openDoors = mutableSetOf<Door>()
    private val cycledDoors = mutableSetOf<Door>()
    private var seatChanges = 0
    private var pendingQuestion: Effect.AskDriver? = null
    /** 대기 경험: 픽업 지점 도착 시각, 마지막 대기 알림 단계, 긴 대기 질문 */
    private var arrivedAt: Long? = null
    private var waitStepsSent = 0
    private var pendingLongWait: Effect.AskLongWait? = null
    private var longWaitAsked = false
    private var lastSaid = ""
    /** 다음 지점 근처에 들어옴 (아직 P 전). P가 되면 도착으로 전환한다. */
    private var nearStop = false
    private val replies = ArrayDeque<String>()
    /** 현재 지점의 기준 도착 시각(epoch ms)과 이미 알린 지연(초) */
    private var baselineArrival: Long? = null
    private var notifiedDelay = 0
    var currentDelayMinutes = 0; private set
    val hasEtaBaseline get() = baselineArrival != null

    private val stop get() = plan.stops.getOrNull(currentStop)

    // ---------------------------------------------------------------- 입력

    /** 주차 중 "픽업 시작". 경로를 설정하고 전원에게 순서를 알린다. */
    fun start() {
        if (phase != Phase.PLANNED) return
        if (gear != Gear.PARK && gear != Gear.UNKNOWN) { say("픽업 시작은 주차 중에만 할 수 있어요."); return }
        phase = Phase.READY
        requestRouteWindow(0)
        plan.stops.forEachIndexed { i, s ->
            s.riders.forEach { r ->
                notify(r, "planned", if (isDrop(s))
                    "오늘 ${r.name}${josa(r.name, "은", "는")} ${i + 1}번째로 ${s.place}에 데려다 드려요.${if (i == plan.stops.lastIndex) " 마지막 순서예요." else ""}"
                else "${plan.driverName} 차가 오늘 ${i + 1}번째로 ${r.name}${josa(r.name, "을", "를")} 태우러 가요. 장소: ${s.place}")
            }
        }
        say("${if (plan.stops.any(::isDrop)) "픽드랍" else "픽업"} ${plan.stops.size}곳, ${riderCount()}명입니다. 출발하면 차례대로 알려드릴게요.")
    }

    fun onGear(g: Gear) {
        val prev = gear
        gear = g
        if (g == prev) return
        when (g) {
            Gear.DRIVE -> onDepart()
            Gear.PARK -> onPark()
            else -> Unit
        }
    }

    /** 다음 픽업 지점까지 남은 시간(초). 내비 remainTime, 지오펜스 접근, 데모 주입이 모두 여기로 온다. */
    fun onApproaching(remainSeconds: Int) {
        if (phase != Phase.DRIVING_TO_STOP || remainSeconds > SOON_SECONDS) return
        if (!soonSent.add(currentStop)) return
        val s = stop ?: return
        val minutes = ((remainSeconds + 59) / 60).coerceAtLeast(1)
        s.riders.forEach { r ->
            set(r, RiderStatus.SOON)
            notify(r, "soon", if (isDrop(s)) "${minutes}분 뒤 ${s.place}에 도착해요. ${r.name}${josa(r.name, "과", "와")} 같이 가요${hint(s)}.${lastNote(r)}"
                else "${minutes}분 뒤 도착해요. ${plan.vehicle}${hint(s)}.${lastNote(r)}")
        }
        say("${names(s)}에게 곧 도착한다고 알렸어요.")
    }

    /**
     * 다음 픽업 지점까지의 새 도착 예정(남은 초). 내비 onDrivingInfo/getWaypointInfo, 데모 주입이 여기로 온다.
     * reason: 정체 설명 (예: "강남대로 2.1km 정체"). 없으면 생략.
     */
    fun onEta(remainSeconds: Int, reason: String? = null) {
        if (phase != Phase.DRIVING_TO_STOP) return
        val s = stop ?: return
        val eta = now() + remainSeconds * 1000L
        val base = baselineArrival ?: run { baselineArrival = eta; return }
        if (currentStop in soonSent) return // 곧 도착 이후엔 지연 알림 안 함
        val delay = ((eta - base) / 1000).toInt()
        if (delay < LATE_SECONDS || delay - notifiedDelay < RENOTIFY_SECONDS) return
        notifiedDelay = delay
        val minutes = (delay + 30) / 60
        currentDelayMinutes = minutes
        val why = reason?.let { "$it 때문에 " } ?: "길이 막혀서 "
        s.riders.forEach { r ->
            set(r, RiderStatus.DELAYED)
            notify(r, "late", if (isDrop(s)) "${why}${minutes}분 늦어요. ${r.name}${josa(r.name, "과", "와")} 같이 가고 있으니 천천히 준비하세요."
                else "${why}${minutes}분 늦어요. ${r.name}${josa(r.name, "은", "는")} ${s.place} 안에서 기다려 주세요.")
        }
        say("${names(s)} 쪽에 ${minutes}분 늦는다고 알렸어요.")
    }

    /**
     * 다음 지점 근처 도착 (내비 onWayPointArrived / 지오펜스 150m ENTER / 데모 주입). 중복 호출은 무시.
     * 이미 P면 바로 도착·대기, 아니면 P가 될 때 도착·대기로 전환한다.
     */
    fun onArrivedAtStop() {
        if (phase != Phase.DRIVING_TO_STOP) return
        nearStop = true
        if (gear == Gear.PARK) arrive() else log("지점 근처 도착 — P가 되면 도착 처리")
    }

    private fun arrive() {
        val s = stop ?: return
        nearStop = false
        phase = Phase.AT_STOP
        openDoors.clear(); cycledDoors.clear(); seatChanges = 0
        arrivedAt = now(); waitStepsSent = 0; longWaitAsked = false; pendingLongWait = null
        emit(Effect.PrepareCabin(currentStop))
        s.riders.forEach { r ->
            set(r, RiderStatus.CAR_HERE)
            notify(r, "arrived", if (isDrop(s)) "${s.place}에 도착했어요. ${plan.driverName}${josa(plan.driverName, "이", "가")} ${r.name}${josa(r.name, "과", "와")} 기다리고 있어요${hint(s)}.${lastNote(r)}"
                else "차가 도착했어요. ${plan.vehicle}${hint(s)}.${lastNote(r)}")
        }
        say("${s.place}입니다. ${names(s)} ${if (isDrop(s)) "내려주고" else "타면"} 출발하세요.")
    }

    fun onDestinationArrived() {
        if (phase != Phase.DRIVING_TO_DESTINATION) return
        phase = Phase.ARRIVED
        broadcast("done", "${plan.destination.name}에 도착했어요.")
        say("${plan.destination.name}에 도착했습니다.")
    }

    fun onDoor(door: Door, open: Boolean) {
        val counts = door.countsForBoarding || (door == Door.TRUNK && plan.trunkCounts)
        if (phase != Phase.AT_STOP || gear != Gear.PARK || !counts) {
            log("문 이벤트 무시: $door open=$open phase=$phase gear=$gear"); return
        }
        if (open) {
            // 같은 문이 다시 열리면 그 문의 사이클은 무효 (짐만 넣고 닫았다 다시 여는 경우 등)
            openDoors += door; cycledDoors -= door; return
        }
        if (openDoors.remove(door)) {
            cycledDoors += door
            stop?.riders?.filter { status[it.id] == RiderStatus.CAR_HERE }?.forEach { set(it, RiderStatus.CANDIDATE) }
            log("문 열림→닫힘: $door (사이클 ${cycledDoors.size})")
        }
    }

    /** 착좌 또는 안전벨트 값이 바뀜. 값의 방향은 보지 않는다. */
    fun onSeatOrBeltChanged() {
        if (phase != Phase.AT_STOP || !plan.useSeatSignals) return
        seatChanges++
        val s = stop ?: return
        val waiting = s.riders.filter { status[it.id] == RiderStatus.CAR_HERE || status[it.id] == RiderStatus.CANDIDATE }
        if (waiting.isEmpty() || !autoConfirmable(s)) return
        // 좌석 변화 1회 = 1명. 남은 사람 중 앞사람부터 확정.
        complete(currentStop, waiting.take(1), Evidence.SEAT_OR_BELT_CHANGE)
    }

    /**
     * 대기 경험: 주기적으로(예: 15초) 호출. 차가 먼저 도착해 정차한 채 아이를 기다리는 동안
     *  - WAIT_NOTIFY_SECONDS(3분)가 지나면 보호자에게 "차가 정문 앞에서 기다려요 · 3분째"를 **한 번만**
     *    (3분마다 반복하지 않는다 — 알림 피로. 경과 시간은 차량 화면에만 계속 표시)
     *  - 같은 때 운전자에게 음성 한 번
     *  - LONG_WAIT_SECONDS(10분)가 되면 주차 중 화면에 "보호자에게 알리기 / 계속 기다리기" 한 번
     * 문이 열리기 시작하면(탑승 후보) 대기 알림을 멈춘다.
     */
    fun onTick() {
        if (phase != Phase.AT_STOP || gear != Gear.PARK) return
        val s = stop ?: return
        val since = arrivedAt ?: return
        val waiting = s.riders.filter { status[it.id] == RiderStatus.CAR_HERE }
        if (waiting.isEmpty()) return
        val sec = ((now() - since) / 1000).toInt()
        if (sec >= WAIT_NOTIFY_SECONDS && waitStepsSent == 0) {
            waitStepsSent = 1
            val min = sec / 60
            waiting.forEach { r ->
                notify(r, "waiting", if (isDrop(s)) "${plan.driverName}${josa(plan.driverName, "이", "가")} ${s.place}${hint(s)}에서 ${r.name}${josa(r.name, "과", "와")} 기다려요 · ${min}분째"
                    else "${plan.driverName} 차가 ${s.place}${hint(s)}에서 기다려요 · ${min}분째")
            }
            say("${names(waiting)}${josa(waiting.last().name, "이", "가")} 아직이에요. ${min}분째 기다리는 중이에요.")
        }
        if (sec >= LONG_WAIT_SECONDS && !longWaitAsked) {
            longWaitAsked = true
            val q = Effect.AskLongWait(currentStop, waiting, sec / 60)
            pendingLongWait = q
            emit(q)
        }
    }

    /** DEMO 전용: 대기 시간을 seconds 만큼 앞당긴다 (발표에서 3분을 실제로 기다릴 수 없으므로). */
    fun advanceWaitForDemo(seconds: Int) {
        arrivedAt = arrivedAt?.minus(seconds * 1000L)
        onTick()
    }

    /** 긴 대기 질문 응답 (주차 중). notifyGuardian=true면 보호자에게 "아직 나오지 않았어요"를 보낸다. */
    fun answerLongWait(notifyGuardian: Boolean) {
        val q = pendingLongWait ?: return
        if (gear != Gear.PARK) return
        pendingLongWait = null
        if (notifyGuardian) {
            val s = plan.stops[q.stopIndex]
            q.riders.forEach { r ->
                notify(r, "still_waiting", "${r.name}${josa(r.name, "이", "가")} ${q.minutes}분째 나오지 않아요. ${s.place}에 확인해 주세요.")
            }
            say("${names(q.riders)} 보호자에게 알렸어요.")
        }
    }

    val waitingSeconds: Int get() =
        if (phase == Phase.AT_STOP && stop?.riders?.any { status[it.id] == RiderStatus.CAR_HERE } == true)
            ((now() - (arrivedAt ?: now())) / 1000).toInt() else 0

    /** 주차 중 운전자 확인. 주행 중이면 무시한다. */
    fun answer(stopIndex: Int, boarded: Boolean) {
        if (gear != Gear.PARK) return
        val s = plan.stops.getOrNull(stopIndex) ?: return
        val targets = s.riders.filter { status[it.id] == RiderStatus.UNCONFIRMED }
        if (targets.isEmpty()) return
        pendingQuestion = null
        if (boarded) complete(stopIndex, targets, Evidence.DRIVER_CONFIRMED)
        else targets.forEach { r ->
            set(r, RiderStatus.MISSED)
            notify(r, "missed", if (isDrop(s)) "${r.name}${josa(r.name, "을", "를")} 이번에 내려드리지 못했어요. ${plan.driverName}에게 연락해 주세요."
                else "이번에는 만나지 못했어요. ${plan.driverName}에게 연락해 주세요.")
        }
    }

    /** 탑승자가 웹에서 "N분 늦어요"를 누름. 운전자에게는 음성으로만 전달한다. */
    fun onRiderLate(riderId: String, minutes: Int) {
        val r = rider(riderId) ?: return
        if (status[r.id] == RiderStatus.BOARDED || status[r.id] == RiderStatus.DROPPED) return
        say("${r.name}${josa(r.name, "이", "가")} ${minutes}분 늦는대요.")
    }

    /**
     * 보호자 빠른 답장. 확인성 답장("넵", "천천히 오세요")은 운전자에게 읽어주지 않고 화면에만 남긴다.
     * "received"(받았어요)는 드랍 지점에서 인계 확정 근거가 된다 — 문만 열고 안 내린 경우를 문·기어로는 구분할 수 없기 때문.
     */
    fun onRiderReply(riderId: String, code: String) {
        val r = rider(riderId) ?: return
        val who = r.guardian ?: "${r.name} 보호자"
        val label = REPLY_LABEL[code] ?: code
        replies.addFirst("$who: $label"); while (replies.size > 5) replies.removeLast()
        log("💬 $who: $label (음성 안내 없음)")
        if (code == "received") {
            val i = plan.stops.indexOfFirst { s -> s.riders.any { it.id == r.id } }
            val s = plan.stops.getOrNull(i) ?: return
            if (phase == Phase.AT_STOP && i == currentStop && isDrop(s) &&
                status[r.id] in setOf(RiderStatus.CAR_HERE, RiderStatus.CANDIDATE)) {
                complete(i, listOf(r), Evidence.GUARDIAN_CONFIRMED)
            }
        }
    }

    // ---------------------------------------------------------------- 내부 전이

    private fun onDepart() {
        when (phase) {
            Phase.READY -> {
                phase = Phase.DRIVING_TO_STOP
                currentStop = 0
                broadcast("departed", "${plan.driverName} 차가 출발했어요.")
                announceNext()
            }
            Phase.AT_STOP -> {
                val s = stop!!
                val left = s.riders.filter { status[it.id] in setOf(RiderStatus.CAR_HERE, RiderStatus.CANDIDATE) }
                val candidates = left.filter { status[it.id] == RiderStatus.CANDIDATE }
                // 1명이면 문 사이클 1회로 확정. 같은 보호자의 형제는 함께 확정(한 문으로 같이 타는 경우가 많음).
                val n = if (s.riders.size > 1 && cycledDoors.isNotEmpty()) candidates.size else cycledDoors.size
                if (!autoConfirmable(s)) {
                    left.forEach { set(it, RiderStatus.UNCONFIRMED) }
                    log("한 지점 여러 보호자 — 자동 확정하지 않음")
                } else if (candidates.isNotEmpty() && n > 0) {
                    complete(currentStop, candidates.take(n), Evidence.DOOR_CYCLE_THEN_DRIVE)
                    (left - candidates.take(n).toSet()).forEach { set(it, RiderStatus.UNCONFIRMED) }
                } else {
                    left.forEach { set(it, RiderStatus.UNCONFIRMED) }
                }
                if (s.riders.any { status[it.id] == RiderStatus.UNCONFIRMED }) {
                    say("${names(s.riders.filter { status[it.id] == RiderStatus.UNCONFIRMED })} ${if (isDrop(s)) "인계" else "탑승"}은 다음에 멈출 때 확인할게요.")
                }
                advance()
            }
            else -> Unit
        }
    }

    private fun onPark() {
        if (phase == Phase.DRIVING_TO_STOP && nearStop) arrive()
        // 미확정 탑승자가 있으면 주차 중에 한 번만 묻는다.
        val idx = plan.stops.indexOfFirst { s -> s.riders.any { status[it.id] == RiderStatus.UNCONFIRMED } }
        if (idx >= 0) {
            val q = Effect.AskDriver(idx, plan.stops[idx].riders.filter { status[it.id] == RiderStatus.UNCONFIRMED })
            pendingQuestion = q
            emit(q)
            say("${names(q.riders)} ${if (isDrop(plan.stops[idx])) "내려드렸나요" else "탔나요"}? 화면에서 알려주세요.")
        }
    }

    private fun advance() {
        currentStop++
        if (currentStop < plan.stops.size) {
            phase = Phase.DRIVING_TO_STOP
            if (currentStop >= windowStart + WINDOW) requestRouteWindow(currentStop)
            announceNext()
        } else {
            phase = Phase.DRIVING_TO_DESTINATION
            // 최종 목적지는 매 경로 설정에 포함돼 있으므로 다시 설정하지 않는다.
            val all = plan.stops.flatMap { it.riders }
            if (all.all { status[it.id] == RiderStatus.BOARDED || status[it.id] == RiderStatus.DROPPED }) {
                val anyDrop = plan.stops.any(::isDrop); val anyPick = plan.stops.any { !isDrop(it) }
                val msg = when { anyDrop && anyPick -> "오늘 픽드랍을 모두 마쳤어요."; anyDrop -> "모두 내려드렸어요."; else -> "모두 탔어요." }
                broadcast("all_boarded", "$msg ${plan.destination.name}${ro(plan.destination.name)} 출발합니다.")
                say("$msg ${plan.destination.name}${ro(plan.destination.name)} 안내합니다.")
            } else {
                say("${plan.destination.name}${ro(plan.destination.name)} 안내합니다.")
            }
        }
    }

    private fun announceNext() {
        val s = stop ?: return
        nearStop = false
        baselineArrival = null; notifiedDelay = 0; currentDelayMinutes = 0
        s.riders.forEach { r ->
            set(r, RiderStatus.NEXT)
            notify(r, "next", "이제 ${r.name} 차례예요. ${s.place}${ro(s.place)} ${if (isDrop(s)) "데려다 드리러 " else ""}가고 있어요.${lastNote(r)}")
        }
        // 순서 공유: 그 뒤 지점들에는 "앞에 N곳 남았어요" (다른 집 이름·주소는 노출하지 않고 숫자만)
        plan.stops.drop(currentStop + 1).forEachIndexed { k, later ->
            later.riders.forEach { r -> notify(r, "queue", "${r.name} 앞에 ${k + 1}곳 남았어요.${lastNote(r)}") }
        }
        say("다음은 ${names(s)}, ${s.place}.")
    }

    /** 마지막 순서면 "천천히 내려오세요" (실제 기사 문자: "아주 천천히 내려오세요 토리가 마지막이에요") */
    private fun lastNote(r: Rider): String {
        val i = plan.stops.indexOfFirst { s -> s.riders.any { it.id == r.id } }
        return if (i == plan.stops.lastIndex && plan.stops.size > 1) " ${r.name}${josa(r.name, "이", "가")} 마지막이에요. 천천히 나오세요." else ""
    }

    private fun isDrop(s: Stop) = s.type == StopType.DROPOFF

    /** 자동 확정 가능: 1명뿐이거나, 여럿이어도 보호자가 같음(형제). 보호자 정보가 없으면 불가. */
    private fun autoConfirmable(s: Stop): Boolean =
        s.riders.size <= 1 || s.riders.map { it.guardian }.let { g -> g.all { it != null } && g.distinct().size == 1 }

    private fun complete(stopIndex: Int, riders: List<Rider>, evidence: Evidence) {
        if (riders.isEmpty()) return
        val drop = isDrop(plan.stops[stopIndex])
        val time = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }
        riders.forEach { r ->
            set(r, if (drop) RiderStatus.DROPPED else RiderStatus.BOARDED)
            if (drop) notify(r, "dropped", "${r.name} 내려드렸어요 ($time).") else notify(r, "boarded", "${r.name} 탔어요.")
        }
        emit(Effect.Boarded(stopIndex, riders, evidence, dropoff = drop))
        say("${names(riders)} ${if (drop) "내려드렸어요" else "탔어요"}.")
    }

    private fun requestRouteWindow(from: Int) {
        windowStart = from
        emit(Effect.SetRoute(plan.stops.drop(from).take(WINDOW), plan.destination))
    }

    // ---------------------------------------------------------------- 유틸

    fun snapshot() = Snapshot(phase, gear, currentStop, status.toMap(), pendingQuestion, lastSaid, waitingSeconds, pendingLongWait, replies.toList())

    private fun set(r: Rider, s: RiderStatus) { status[r.id] = s }
    private fun rider(id: String) = plan.stops.flatMap { it.riders }.firstOrNull { it.id == id }
    private fun riderCount() = plan.stops.sumOf { it.riders.size }
    private fun names(s: Stop) = names(s.riders)
    private fun names(rs: List<Rider>) = rs.joinToString(", ") { it.name }
    private fun hint(s: Stop) = if (s.meetHint.isBlank()) "" else " · ${s.meetHint}"
    private fun say(t: String) { lastSaid = t; emit(Effect.Say(t)) }
    private fun log(t: String) = emit(Effect.Log(t))
    private fun broadcast(kind: String, text: String) = emit(Effect.Notify(Effect.ALL, kind, text))
    private fun notify(r: Rider, kind: String, text: String) = emit(Effect.Notify(r.id, kind, text))

    companion object {
        const val SOON_SECONDS = 180
        const val LATE_SECONDS = 300
        const val RENOTIFY_SECONDS = 180
        const val WAIT_NOTIFY_SECONDS = 180
        const val LONG_WAIT_SECONDS = 600
        /** Pleos NaviHelper WaypointIndex = FIRST, SECOND */
        const val WINDOW = 2
        val REPLY_LABEL = mapOf("ok" to "넵", "slow" to "천천히 오세요", "received" to "받았어요")

        /** 방향 조사: 받침 없거나 ㄹ받침이면 "로", 그 외 "으로" (학원으로 / 집으로 / 서울로) */
        fun ro(word: String): String {
            val c = word.lastOrNull() ?: return "로"
            if (c !in '가'..'힣') return "로"
            val jong = (c - '가') % 28
            return if (jong == 0 || jong == 8) "로" else "으로"
        }

        /** 받침 유무로 조사 선택 (지우를 / 하윤을). 한글이 아니면 뒤 조사. */
        fun josa(word: String, withBatchim: String, without: String): String {
            val c = word.lastOrNull() ?: return without
            if (c !in '가'..'힣') return without
            return if ((c - '가') % 28 != 0) withBatchim else without
        }
    }
}
