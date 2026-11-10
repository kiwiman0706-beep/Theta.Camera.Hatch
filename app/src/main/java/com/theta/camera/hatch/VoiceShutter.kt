package com.theta.camera.hatch

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 声シャッター（CAM-026）：「はいチーズ」などを聞き取ったら onHit。
 * 聞き取りは端末内の認識を優先し、終わるたびに聞き直す。録画中はマイクが競合するので止めておく。
 */
class VoiceShutter(private val context: Context, private val onHit: () -> Unit) {
    private val words = listOf("チーズ", "ちーず", "cheese", "撮って", "とって", "シャッター")
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var lastHit = 0L
    var running = false
        private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(context) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    fun start() {
        if (running) return
        running = true
        recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
        listen()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }

    private fun listen() {
        if (!running) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        runCatching { recognizer?.startListening(i) }.onFailure { again(1000) }
    }

    private fun again(ms: Long) { handler.removeCallbacksAndMessages(null); handler.postDelayed({ listen() }, ms) }

    private fun check(b: Bundle?) {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (texts.any { t -> words.any { t.contains(it, ignoreCase = true) } } && SystemClock.elapsedRealtime() - lastHit > 1500) {
            lastHit = SystemClock.elapsedRealtime()
            onHit()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) = check(b)
        override fun onResults(b: Bundle?) { check(b); again(150) }
        override fun onError(error: Int) { again(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 300L) }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }
}
