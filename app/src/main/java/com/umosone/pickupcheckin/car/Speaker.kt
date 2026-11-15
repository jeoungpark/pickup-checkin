package com.umosone.pickupcheckin.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import java.util.Locale

/** 운전자 음성 안내. ON_DEVICE 모드 (앱 등록 없이 에뮬레이터에서 동작 실측). 초기화 전 문장은 큐에 쌓는다. */
class Speaker(ctx: Context, private val log: (String) -> Unit) {
    private val tts = TextToSpeech(ctx.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var speaking = false
    private val queue = ArrayDeque<String>()

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() { speaking = true }
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { speaking = false; next() }
        override fun onStop() { speaking = false; next() }
        override fun onError(errorType: ErrorType) { speaking = false; log("TTS 오류: $errorType"); next() }
    }

    fun start() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(events); tts.setLocale(Locale.KOREA); ready = true; next()
            }
            override fun onFailure(errorType: ErrorType) { log("TTS 초기화 실패: $errorType") }
        })
    }

    fun say(text: String) {
        synchronized(queue) { queue.addLast(text) }
        if (ready && !speaking) next()
    }

    private fun next() {
        val t = synchronized(queue) { queue.removeFirstOrNull() } ?: return
        speaking = true
        runCatching { tts.speak(t, true) }.onFailure { speaking = false; log("TTS speak 실패: ${it.message}") }
    }

    fun release() { runCatching { tts.stop(); tts.removeEventListener(events); tts.release() } }
}
