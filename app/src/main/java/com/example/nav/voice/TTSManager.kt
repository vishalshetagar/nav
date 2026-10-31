package com.example.nav.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.*

class TTSManager(private val context: Context, private val onInit: (Boolean) -> Unit) {
    private var tts: TextToSpeech? = null
    private val TAG = "TTSManager"
    private var isInitialized = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                Log.d(TAG, "TTS Initialized successfully")
                onInit(true)
            } else {
                Log.e(TAG, "TTS Initialization failed")
                onInit(false)
            }
        }
    }

    fun speak(text: String, voiceId: String? = null) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }

        if (voiceId.isNullOrBlank()) {
            val language = context.getSharedPreferences("nav_prefs", Context.MODE_PRIVATE).getString("preferred_language", "en-US") ?: "en-US"
            tts?.language = Locale.forLanguageTag(language)
        } else setVoiceById(voiceId)
        
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NavTTS")
    }

    fun stop() {
        tts?.stop()
    }

    fun getAvailableVoices(): List<VoiceInfo> {
        return tts?.voices?.map { voice ->
            VoiceInfo(voice.name, voice.locale.displayName, voice)
        } ?: emptyList()
    }

    private fun setVoiceById(voiceId: String) {
        val voice = tts?.voices?.find { it.name == voiceId }
        if (voice != null) {
            tts?.voice = voice
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
    }
}

data class VoiceInfo(
    val id: String,
    val displayName: String,
    val voice: Voice
)
