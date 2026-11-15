package com.umosone.pickupcheckin.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.umosone.pickupcheckin.car.PickupService
import com.umosone.pickupcheckin.core.Gear
import com.umosone.pickupcheckin.core.Phase
import com.umosone.pickupcheckin.core.RiderStatus

/**
 * 픽업 체크인 차량 화면. 상태는 [PickupService.Runtime] 이 갖고, 화면은 관찰·표시만 한다.
 *  - 주행 중(P가 아닐 때): 상태 표시와 음성만. 터치 입력 없음.
 *  - 주차 중: 픽업 시작, 미확정 탑승 확인, 긴 대기 확인 (각각 한 번 터치).
 *  - 아래 DEMO 패널: 에뮬레이터 내비가 목업이라 정체·3분 전·도착을 주입하는 발표자용 패널.
 *
 * adb: am start -n com.umosone.pickupcheckin/.ui.MainActivity --es plan friends --ez autostart true
 */
class MainActivity : Activity() {

    private val rt get() = PickupService.runtime

    private lateinit var titleView: TextView
    private lateinit var gearView: TextView
    private lateinit var phaseView: TextView
    private lateinit var sayView: TextView
    private lateinit var waitView: TextView
    private lateinit var ridersBox: LinearLayout
    private lateinit var questionBox: LinearLayout
    private lateinit var questionText: TextView
    private lateinit var longWaitBox: LinearLayout
    private lateinit var longWaitText: TextView
    private lateinit var startBtn: Button
    private lateinit var logView: TextView
    private val observer: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissions(arrayOf("pleos.car.permission.FUSED_LOCATION", "android.permission.POST_NOTIFICATIONS"), 1)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); forward(intent) }

    /** 화면으로 들어온 plan/autostart/demo 를 서비스로 넘긴다. */
    private fun forward(i: Intent?) {
        val svc = Intent(this, PickupService::class.java)
        i?.extras?.let { svc.putExtras(it) }
        startForegroundService(svc)
        window.decorView.postDelayed({ rt?.observe(observer); render() }, 500)
    }

    override fun onResume() { super.onResume(); rt?.observe(observer); render() }
    override fun onPause() { super.onPause(); rt?.unobserve(observer) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(BG)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = text("픽업 체크인", 26f, FG, bold = true)
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        phaseView = text("", 18f, MUTED).also { header.addView(it) }
        gearView = text("", 22f, Color.WHITE, bold = true).apply { setPadding(dp(14), dp(4), dp(14), dp(4)); background = pill(Color.DKGRAY) }
        header.addView(gearView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(16) })
        root.addView(header)

        sayView = text("", 30f, FG, bold = true).apply { setPadding(dp(20), dp(18), dp(20), dp(18)); background = card(CARD) }
        root.addView(sayView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        waitView = text("", 20f, Color.parseColor("#6A1B9A"), bold = true).apply { visibility = View.GONE }
        root.addView(waitView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        ridersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ridersBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        questionText = text("", 26f, FG, bold = true)
        questionBox = promptBox(questionText,
            bigButton("탔어요") { answer(true) }, bigButton("안 탔어요") { answer(false) })
        root.addView(questionBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        longWaitText = text("", 24f, FG, bold = true)
        longWaitBox = promptBox(longWaitText,
            bigButton("보호자에게 알리기") { rt?.engine?.answerLongWait(true); render() },
            bigButton("계속 기다리기") { rt?.engine?.answerLongWait(false); render() })
        root.addView(longWaitBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        startBtn = bigButton("오늘 픽업 시작 (지난번 그대로)") { rt?.engine?.start(); render() }
        root.addView(startBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        // 발표자용 DEMO 패널 — 숨기지 않고 라벨을 단다. 서비스의 같은 입구로 들어간다.
        val demo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = card(Color.parseColor("#EEF1F4"))
            addView(text("DEMO · 이벤트 주입\n(에뮬레이터 내비 목업 대체)", 13f, MUTED))
            addView(smallButton("정체 +8분") { rt?.demo("delay:8") })
            addView(smallButton("3분 전") { rt?.demo("soon") })
            addView(smallButton("지점 도착") { rt?.demo("arrive") })
            addView(smallButton("목적지 도착") { rt?.demo("dest") })
        }
        root.addView(demo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        logView = text("", 13f, MUTED).apply { typeface = Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun render() {
        val r = rt ?: return
        val s = r.engine.snapshot()
        val parked = s.gear == Gear.PARK
        titleView.text = r.plan.title
        gearView.text = when (s.gear) { Gear.PARK -> "P 주차"; Gear.DRIVE -> "D 주행"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; else -> "—" }
        gearView.background = pill(if (parked) Color.parseColor("#2E7D32") else Color.parseColor("#1565C0"))
        phaseView.text = PHASE_LABEL[s.phase]
        sayView.text = s.lastSaid.ifBlank { "주차 중에 '오늘 픽업 시작'을 누르세요." }

        val waitLine = if (s.waitingSeconds > 0) "기다리는 중 · ${s.waitingSeconds / 60}분 ${s.waitingSeconds % 60}초 (3분마다 자동 안내)" else ""
        val replyLine = s.replies.take(2).joinToString("   ") { "💬 $it" }
        waitView.text = listOf(waitLine, replyLine).filter { it.isNotBlank() }.joinToString("\n")
        waitView.visibility = if (waitView.text.isNotBlank()) View.VISIBLE else View.GONE

        ridersBox.removeAllViews()
        r.plan.stops.forEachIndexed { i, stop ->
            stop.riders.forEach { rider ->
                val st = s.statuses[rider.id] ?: RiderStatus.WAITING
                val active = i == s.currentStop && s.phase in setOf(Phase.DRIVING_TO_STOP, Phase.AT_STOP)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10)); background = card(if (active) Color.parseColor("#E3F2FD") else CARD)
                }
                row.addView(text("${i + 1}", 22f, MUTED, bold = true), LinearLayout.LayoutParams(dp(40), WRAP_CONTENT))
                row.addView(text(rider.name, 24f, FG, bold = true), LinearLayout.LayoutParams(dp(120), WRAP_CONTENT))
                row.addView(text(stop.place, 18f, MUTED), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(text(STATUS_LABEL[st] ?: st.name, 20f, Color.WHITE, bold = true).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4)); background = pill(STATUS_COLOR[st] ?: Color.GRAY)
                })
                ridersBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }

        // 확인 질문은 주차 중에만
        val q = s.pendingQuestion
        questionBox.visibility = if (q != null && parked) View.VISIBLE else View.GONE
        if (q != null) questionText.text = "${q.riders.joinToString { it.name }} " +
            if (r.plan.stops[q.stopIndex].type == com.umosone.pickupcheckin.core.StopType.DROPOFF) "내려드렸나요?" else "탔나요?"
        val lw = s.pendingLongWait
        longWaitBox.visibility = if (lw != null && parked) View.VISIBLE else View.GONE
        if (lw != null) longWaitText.text = "${lw.riders.joinToString { it.name }} ${lw.minutes}분째 기다리는 중"

        startBtn.visibility = if (s.phase == Phase.PLANNED) View.VISIBLE else View.GONE
        startBtn.isEnabled = parked || s.gear == Gear.UNKNOWN
        logView.text = r.logs.joinToString("\n")
    }

    private fun answer(boarded: Boolean) {
        val e = rt?.engine ?: return
        val q = e.snapshot().pendingQuestion ?: return
        e.answer(q.stopIndex, boarded); render()
    }

    // ------------------------------------------------------------ 유틸

    private fun promptBox(label: TextView, vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12)); background = card(Color.parseColor("#FFF4D6")); visibility = View.GONE
        addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.forEach { addView(it) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(40).toFloat() }
    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 22f; minHeight = dp(64); setPadding(dp(24), 0, dp(24), 0); setOnClickListener { onClick() }
    }
    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; setOnClickListener { onClick() }
    }

    companion object {
        private val BG = Color.parseColor("#F7F8FA")
        private val CARD = Color.WHITE
        private val FG = Color.parseColor("#111418")
        private val MUTED = Color.parseColor("#5F6B76")
        private val PHASE_LABEL = mapOf(
            Phase.PLANNED to "준비", Phase.READY to "출발 대기", Phase.DRIVING_TO_STOP to "픽업 지점으로 이동",
            Phase.AT_STOP to "픽업 지점 정차", Phase.DRIVING_TO_DESTINATION to "목적지로 이동", Phase.ARRIVED to "도착",
        )
        private val STATUS_LABEL = mapOf(
            RiderStatus.WAITING to "대기", RiderStatus.ON_BOARD to "타고 있음", RiderStatus.DROPPED to "내려드림",
            RiderStatus.NEXT to "다음 차례", RiderStatus.DELAYED to "지연 알림",
            RiderStatus.SOON to "곧 도착 알림", RiderStatus.CAR_HERE to "차 도착", RiderStatus.CANDIDATE to "출발하면 확정",
            RiderStatus.BOARDED to "탔어요", RiderStatus.UNCONFIRMED to "확인 필요", RiderStatus.MISSED to "못 만남",
        )
        private val STATUS_COLOR = mapOf(
            RiderStatus.WAITING to Color.parseColor("#9AA5B1"), RiderStatus.NEXT to Color.parseColor("#1565C0"),
            RiderStatus.ON_BOARD to Color.parseColor("#455A64"), RiderStatus.DROPPED to Color.parseColor("#2E7D32"),
            RiderStatus.DELAYED to Color.parseColor("#AD1457"), RiderStatus.SOON to Color.parseColor("#EF6C00"),
            RiderStatus.CAR_HERE to Color.parseColor("#6A1B9A"), RiderStatus.CANDIDATE to Color.parseColor("#00838F"),
            RiderStatus.BOARDED to Color.parseColor("#2E7D32"), RiderStatus.UNCONFIRMED to Color.parseColor("#C62828"),
            RiderStatus.MISSED to Color.parseColor("#616161"),
        )
    }
}
