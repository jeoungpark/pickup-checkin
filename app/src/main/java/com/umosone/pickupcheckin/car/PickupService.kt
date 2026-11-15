package com.umosone.pickupcheckin.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.umosone.pickupcheckin.BuildConfig
import com.umosone.pickupcheckin.core.Effect
import com.umosone.pickupcheckin.core.PickupEngine
import com.umosone.pickupcheckin.core.Plan
import com.umosone.pickupcheckin.core.PlanJson
import com.umosone.pickupcheckin.net.Relay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 픽업 진행 중 차량 신호를 받는 포그라운드 서비스.
 *
 * 왜 서비스인가: 실차에서는 운전 중 내비 화면이 앞에 있고 이 앱은 뒤에 있다.
 * Activity 에서 신호를 받으면 앱이 백그라운드로 갈 때 프로세스가 멈춰 이벤트가 늦게 처리된다
 * (에뮬레이터 실측: 약 24초 지연 → 지연 알림 누락). 그래서 상태 머신과 SDK 어댑터를 서비스가 가진다.
 *
 * 시작:   am start-foreground-service -n com.umosone.pickupcheckin/.car.PickupService --es plan academy --ez autostart true
 * 데모:   am start-foreground-service -n com.umosone.pickupcheckin/.car.PickupService --es demo "delay:8|soon|arrive|dest"
 */
class PickupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val rt = runtime ?: Runtime(this, intent?.getStringExtra("plan") ?: "academy").also { runtime = it; it.start() }
        if (intent?.getBooleanExtra("autostart", false) == true) rt.main.postDelayed({ rt.engine.start(); rt.changed() }, 3000)
        intent?.getStringExtra("demo")?.let { rt.demo(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime?.release(); runtime = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "픽업 진행", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("픽업 체크인").setContentText("탑승·지연·대기를 확인하고 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    /** 상태 머신 + SDK 어댑터 묶음. 화면(MainActivity)은 이 객체를 관찰만 한다. */
    class Runtime(ctx: Context, planName: String) : ArrivalSink {
        val main = Handler(Looper.getMainLooper())
        val plan: Plan = PlanJson.parse(ctx.assets.open("plans/$planName.json").bufferedReader().readText())
        val isEmulator = Build.FINGERPRINT.contains("avd", ignoreCase = true) || Build.FINGERPRINT.contains("generic")
        val logs = ArrayDeque<String>()
        private val listeners = mutableSetOf<() -> Unit>()

        val speaker = Speaker(ctx, ::log)
        val relay = Relay(BuildConfig.RELAY_URL,
            onLate = { id, m -> engine.onRiderLate(id, m); changed() },
            onReply = { id, code -> engine.onRiderReply(id, code); changed() },
            log = ::log)
        val engine = PickupEngine(plan, emit = ::handle)
        val vehicle = VehicleSignals(ctx,
            onGear = { g -> log("REAL·기어 $g"); engine.onGear(g); replayEarlySignals(); changed() },
            onDoor = { d, open -> log("REAL·문 $d ${if (open) "열림" else "닫힘"}"); engine.onDoor(d, open); changed() },
            onSeatOrBeltChanged = { k -> log("REAL·좌석 변화 $k"); engine.onSeatOrBeltChanged(); changed() },
            log = ::log,
        )
        val navi = NaviSource(ctx, this, ::log)
        val geofence = GeofenceSource(ctx, this, ::log)

        fun start() {
            speaker.start()
            vehicle.useSeatSignals = !isEmulator && plan.useSeatSignals; vehicle.start()
            navi.useEvents = !isEmulator; navi.start()
            geofence.start(plan)
            relay.postPlan(PlanJson.toJson(plan)); relay.startInbox()
            tick()
            log("플랜: ${plan.title} · 에뮬레이터=$isEmulator (내비 이벤트·착좌 보조 신호 ${if (isEmulator) "끔" else "켬"})")
        }

        /** 대기 경험 타이머 */
        private fun tick() {
            engine.onTick(); changed()
            main.postDelayed({ tick() }, TICK_MS)
        }

        fun observe(l: () -> Unit) { listeners += l }
        fun unobserve(l: () -> Unit) { listeners -= l }
        fun changed() = listeners.toList().forEach { it() }

        private fun handle(e: Effect) {
            when (e) {
                is Effect.Say -> { speaker.say(e.text); log("🔊 ${e.text}") }
                is Effect.Notify -> { relay.postEvent(e.to, e.kind, e.text); log("→ ${riderName(e.to)}: ${e.text}") }
                is Effect.SetRoute -> navi.setRoute(e.waypoints, e.destination)
                is Effect.AskDriver -> log("? 운전자 확인 요청: ${e.riders.joinToString { it.name }}")
                is Effect.AskLongWait -> log("? 긴 대기 ${e.minutes}분: ${e.riders.joinToString { it.name }}")
                is Effect.PrepareCabin -> vehicle.prepareCabin()
                is Effect.Boarded -> log("✔ ${if (e.dropoff) "하차·인계" else "탑승"} 확정 ${e.riders.joinToString { it.name }} · 근거 ${e.evidence}")
                is Effect.Log -> log(e.text)
            }
        }

        // ---- 접근/도착/ETA (실제 신호와 데모 주입 공통 입구)
        private val earlyApproach = mutableSetOf<Int>()
        private val earlyArrival = mutableSetOf<Int>()

        override fun approaching(stopIndex: Int, remainSeconds: Int, source: String) {
            if (stopIndex >= 0 && stopIndex != engine.currentStop) { earlyApproach += stopIndex; return }
            log(source); engine.onApproaching(remainSeconds); changed()
        }
        override fun arrived(stopIndex: Int, source: String) {
            if (stopIndex >= 0 && stopIndex != engine.currentStop) { earlyArrival += stopIndex; return }
            log(source); engine.onArrivedAtStop(); changed()
        }
        override fun eta(remainSeconds: Int, reason: String?, source: String) { engine.onEta(remainSeconds, reason); changed() }
        override fun destinationArrived(source: String) { log(source); engine.onDestinationArrived(); changed() }

        private fun replayEarlySignals() {
            val i = engine.currentStop
            if (engine.phase != com.umosone.pickupcheckin.core.Phase.DRIVING_TO_STOP) return
            if (earlyApproach.remove(i)) approaching(i, 170, "REAL·지오펜스 접근 (이미 범위 안)")
            if (earlyArrival.remove(i)) arrived(i, "REAL·지오펜스 도착 (이미 범위 안)")
        }

        /** DEMO: 에뮬레이터 내비 목업 대체 주입 */
        fun demo(cmd: String) {
            when {
                cmd == "soon" -> approaching(-1, 170, "DEMO·3분 전 주입")
                cmd == "arrive" -> arrived(-1, "DEMO·도착 주입")
                cmd == "dest" -> destinationArrived("DEMO·목적지 주입")
                cmd.startsWith("wait:") -> {
                    val min = cmd.substringAfter(":").toIntOrNull() ?: 3
                    log("DEMO·대기 시간 +${min}분 앞당김"); engine.advanceWaitForDemo(min * 60); changed()
                }
                cmd.startsWith("delay:") -> {
                    val min = cmd.substringAfter(":").toIntOrNull() ?: 8
                    log("DEMO·정체 주입 +${min}분 (강남대로 2.1km 정체)")
                    if (!engine.hasEtaBaseline) engine.onEta(600)
                    engine.onEta(600 + min * 60, "강남대로 2.1km 정체"); changed()
                }
            }
        }

        fun riderName(id: String) = if (id == Effect.ALL) "전원" else plan.stops.flatMap { it.riders }.firstOrNull { it.id == id }?.name ?: id

        fun log(msg: String) {
            Log.i("PICKUP", msg)
            val line = "${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())}  $msg"
            main.post { logs.addFirst(line); while (logs.size > 80) logs.removeLast(); changed() }
        }

        fun release() {
            main.removeCallbacksAndMessages(null)
            listOf({ vehicle.release() }, { navi.release() }, { geofence.release() }, { speaker.release() }, { relay.release() })
                .forEach { runCatching(it) }
        }
    }

    companion object {
        private const val CHANNEL = "pickup"
        private const val TICK_MS = 5_000L
        @Volatile var runtime: Runtime? = null; private set
    }
}
