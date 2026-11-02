package com.visionassistant.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Wraps Android SpeechRecognizer. Call listen() from the main thread. Prefers offline recognition. */
class VoiceController(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    @Volatile var listening = false
        private set

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            try { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {}
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError("I did not hear anything")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Microphone permission is needed for voice commands")
                else -> onError("Voice recognition problem. Please try again")
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (list.isNullOrEmpty()) onError("I did not catch that") else onText(list[0])
        }
    }

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen() {
        if (listening) return
        if (!available()) { onError("Speech recognition is not available on this phone"); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        listening = true
        r.startListening(intent)
    }

    fun destroy() { recognizer?.destroy(); recognizer = null; listening = false }
}
