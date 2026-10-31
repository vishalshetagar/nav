package com.example.nav.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One user-initiated recognition session. No background restart loop or transcript logging. */
class VoiceInputManager(private val context: Context) {
    interface VoiceListener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onRmsChanged(rmsdB: Float)
        fun onBufferReceived(buffer: ByteArray?)
        fun onEndOfSpeech()
        fun onError(error: Int)
        fun onResults(results: String)
        fun onPartialResults(partialResults: String)
    }
    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceListener? = null
    private var generation = 0
    fun startListening(language: String = "en-US", listener: VoiceListener) {
        cancel()
        val sessionId = generation
        this.listener = listener
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { listener.onError(SpeechRecognizer.ERROR_CLIENT); return }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    private fun current() = if (sessionId == generation) this@VoiceInputManager.listener else null
                    override fun onReadyForSpeech(params: Bundle?) { current()?.onReadyForSpeech() }
                    override fun onBeginningOfSpeech() { current()?.onBeginningOfSpeech() }
                    override fun onRmsChanged(rmsdB: Float) { current()?.onRmsChanged(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) { current()?.onBufferReceived(buffer) }
                    override fun onEndOfSpeech() { current()?.onEndOfSpeech() }
                    override fun onError(error: Int) {
                        val callback = current()
                        if (sessionId == generation) this@VoiceInputManager.listener = null
                        callback?.onError(error)
                    }
                    override fun onResults(results: Bundle?) {
                        val callback = current()
                        if (sessionId == generation) this@VoiceInputManager.listener = null
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text.isNullOrBlank()) callback?.onError(SpeechRecognizer.ERROR_NO_MATCH) else callback?.onResults(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                            current()?.onPartialResults(it)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                })
            }
        } catch (_: Exception) { this.listener = null; listener.onError(SpeechRecognizer.ERROR_CLIENT) }
    }
    fun cancel() { generation++; listener = null; recognizer?.cancel() }
    fun destroy() { cancel(); recognizer?.destroy(); recognizer = null }
}
