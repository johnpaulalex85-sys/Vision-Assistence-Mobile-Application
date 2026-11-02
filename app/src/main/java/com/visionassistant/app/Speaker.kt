package com.visionassistant.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Pair<String, Boolean>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.setSpeechRate(1.1f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finish(id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { finish(id) }
                    override fun onStop(id: String?, interrupted: Boolean) { id?.let { callbacks.remove(it) } }
                })
                ready = true
                synchronized(pending) {
                    pending.forEach { speak(it.first, it.second) }
                    pending.clear()
                }
            }
        }
    }

    private fun finish(id: String?) {
        id?.let { callbacks.remove(it) }?.let { main.post(it) }
    }

    /** interrupt = true cuts off whatever is being said. onDone runs on the main thread. */
    fun speak(text: String, interrupt: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!ready) {
            synchronized(pending) { pending.add(text to interrupt) }
            return
        }
        val id = "va-${System.nanoTime()}"
        onDone?.let { callbacks[id] = it }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    /** Hazard alerts: urgent ones interrupt, normal ones are dropped if speech is busy (no stale queue). */
    fun alert(text: String, urgent: Boolean) {
        if (!ready) return
        if (urgent) speak(text, true) else if (!tts.isSpeaking) speak(text, false)
    }

    fun stop() { if (ready) tts.stop() }

    fun shutdown() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }
}
