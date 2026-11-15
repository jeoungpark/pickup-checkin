package com.umosone.pickupcheckin.net

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 탑승자 알림 중계 서버(relay/server.py) 클라이언트.
 *  - POST /api/events : 차 → 탑승자 웹 (탔어요, 3분 전 등)
 *  - POST /api/plan   : 오늘 픽업 정보 (탑승자 웹이 이름·장소를 표시)
 *  - GET  /api/inbox  : 탑승자 웹 → 차 ("5분 늦어요")
 * 네트워크 실패는 주행 흐름을 막지 않는다 (로그만 남김).
 */
class Relay(
    private val baseUrl: String,
    private val onLate: (riderId: String, minutes: Int) -> Unit,
    private val onReply: (riderId: String, code: String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var inboxCursor = 0
    private var polling = false

    fun postPlan(plan: JSONObject) = post("/api/plan", plan)

    fun postEvent(to: String, kind: String, text: String) = post("/api/events", JSONObject()
        .put("to", to).put("kind", kind).put("text", text).put("ts", System.currentTimeMillis()))

    private fun post(path: String, body: JSONObject) = io.execute {
        runCatching {
            val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 1500; readTimeout = 1500; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode.also { c.disconnect() }
        }.onFailure { main.post { log("중계 서버 전송 실패($path): ${it.javaClass.simpleName}") } }
    }

    fun startInbox() {
        if (polling) return
        polling = true
        pollInbox()
    }

    private fun pollInbox() {
        if (!polling) return
        io.execute {
            runCatching {
                val c = (URL("$baseUrl/api/inbox?after=$inboxCursor").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1500; readTimeout = 1500
                }
                val arr = JSONArray(c.inputStream.bufferedReader().readText()).also { c.disconnect() }
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    inboxCursor = maxOf(inboxCursor, m.getInt("seq"))
                    when (m.optString("type")) {
                        "late" -> { val rider = m.getString("rider"); val minutes = m.optInt("minutes", 5); main.post { onLate(rider, minutes) } }
                        "reply" -> { val rider = m.getString("rider"); val code = m.optString("code"); main.post { onReply(rider, code) } }
                    }
                }
            }
        }
        main.postDelayed({ pollInbox() }, 2000)
    }

    fun release() { polling = false; main.removeCallbacksAndMessages(null); io.shutdownNow() }
}
