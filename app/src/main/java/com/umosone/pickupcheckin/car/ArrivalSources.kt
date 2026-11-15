package com.umosone.pickupcheckin.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.umosone.pickupcheckin.core.Destination
import com.umosone.pickupcheckin.core.Plan
import com.umosone.pickupcheckin.core.Stop

/**
 * "다음 픽업 지점 접근/도착" 신호 소스.
 * 실제 신호(내비, 지오펜스)와 데모 주입이 모두 같은 [ArrivalSink] 로 들어가 같은 상태 머신을 탄다.
 */
interface ArrivalSink {
    /** stopIndex: 어느 지점에 대한 신호인지 (-1 = 현재 지점). source: 화면·로그 표시용 */
    fun approaching(stopIndex: Int, remainSeconds: Int, source: String)
    fun arrived(stopIndex: Int, source: String)
    /** 다음 지점까지 새 도착 예정(남은 초). 기준보다 늦어지면 상태 머신이 지연 연락을 보낸다. */
    fun eta(remainSeconds: Int, reason: String?, source: String)
    fun destinationArrived(source: String)
}

/** 실제 신호 ① NaviHelper: 경로 설정 + 경유지 도착 + 남은 시간. 실차의 주 신호. */
class NaviSource(ctx: Context, private val sink: ArrivalSink, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(ctx.applicationContext)
    private var connected = false
    /** 에뮬레이터 내비는 목업(좌표 무시, 남은 시간 고정)이라 기본은 경로 설정만 하고 신호는 쓰지 않는다. */
    var useEvents = false

    private val listener = object : NaviHelperEventListener {
        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            if (useEvents) main.post { sink.arrived(-1, "REAL·내비 경유지 도착(${info.index})") }
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            if (useEvents) main.post { sink.destinationArrived("REAL·내비 목적지 도착") }
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {
            val next = list.firstOrNull() ?: return
            if (useEvents) main.post {
                sink.eta(next.remainTime, trafficReason, "REAL·내비 남은 시간 ${next.remainTime}초")
                sink.approaching(-1, next.remainTime, "REAL·내비 남은 시간 ${next.remainTime}초")
            }
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            val s = info.trafficStatus?.summary ?: return
            trafficReason = if (s.totalCongestionDist >= 500) "정체 %.1fkm".format(s.totalCongestionDist / 1000) else null
        }
    }

    fun start() {
        navi.initialize(
            onConnected = { connected = true; navi.addListener(listener); log("내비 연결됨") },
            onFailed = { t -> log("내비 연결 실패: ${t.message}") },
        )
        pollRemainTime()
    }

    private var trafficReason: String? = null

    private fun pollRemainTime() {
        if (connected && useEvents) runCatching { navi.getRouteStateInfo(); navi.getWaypointInfo() }
        main.postDelayed({ pollRemainTime() }, 10_000)
    }

    /** 경유지는 최대 2곳(FIRST, SECOND). 상태 머신이 롤링으로 잘라서 넘겨준다. */
    fun setRoute(waypoints: List<Stop>, dest: Destination) {
        if (!connected) { log("내비 미연결 — 경로 설정 건너뜀"); return }
        val idx = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
        val req = RouteWithWaypointsRequest(
            RouteInfo(latitude = dest.lat, longitude = dest.lon, displayLongitude = null, displayLatitude = null,
                poiName = dest.name, poiId = "", poiSubId = "0", address = dest.address),
            waypoints.take(2).mapIndexed { i, s ->
                RequestWaypointInfo(latitude = s.lat, longitude = s.lon, waypointIndex = idx[i],
                    poiId = "", poiName = s.place, poiSubId = "0", address = s.place)
            },
        )
        runCatching { navi.requestRouteWithWaypoints(req) }
            .onSuccess { log("경로 설정: ${waypoints.joinToString(" → ") { it.place }} → ${dest.name}") }
            .onFailure { log("경로 설정 실패: ${it.message}") }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(listener); navi.release() }
    }
}

/**
 * 실제 신호 ② FusedLocation 지오펜스: 지점마다 접근(1.2km)·도착(150m) 원을 건다.
 * 에뮬레이터에서는 `adb emu geo fix` 로 위치를 옮기면 ENTER 가 실제로 온다(실측).
 */
class GeofenceSource(ctx: Context, private val sink: ArrivalSink, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val fused = FusedLocation(ctx.applicationContext)
    private val alertIds = mutableListOf<String>()

    fun start(plan: Plan) {
        runCatching { fused.initialize() }.onFailure { log("FusedLocation 초기화 실패: ${it.message}"); return }
        plan.stops.forEachIndexed { i, s ->
            add(s.lat, s.lon, APPROACH_M) { log("지오펜스 ENTER: ${i + 1}번 지점 접근원"); sink.approaching(i, 170, "REAL·지오펜스 접근 ${APPROACH_M.toInt()}m") }
            add(s.lat, s.lon, ARRIVE_M) { log("지오펜스 ENTER: ${i + 1}번 지점 도착원"); sink.arrived(i, "REAL·지오펜스 도착 ${ARRIVE_M.toInt()}m") }
        }
        add(plan.destination.lat, plan.destination.lon, ARRIVE_M) { sink.destinationArrived("REAL·지오펜스 목적지") }
        log("지오펜스 ${alertIds.size.coerceAtLeast(plan.stops.size * 2 + 1)}개 등록 요청")
    }

    private fun add(lat: Double, lon: Double, radius: Float, onEnter: () -> Unit) {
        val l = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) { alertIds += alertId }
            override fun onTransition(event: ProximityAlertEvent) {
                if (event.transition == ProximityTransition.ENTER) main.post(onEnter)
            }
            override fun onError(exception: Exception) { log("지오펜스 오류: ${exception.message}") }
        }
        runCatching { fused.addProximityAlert(ProximityAlertRequest(lat, lon, radius, setOf(ProximityTransition.ENTER)), l) }
            .onFailure { log("지오펜스 등록 실패: ${it.message}") }
    }

    fun release() {
        alertIds.forEach { runCatching { fused.removeProximityAlert(it) } }
        runCatching { fused.release() }
    }

    companion object {
        const val APPROACH_M = 1200f  // 도심 30~40km/h 기준 약 2~3분
        const val ARRIVE_M = 150f
    }
}
