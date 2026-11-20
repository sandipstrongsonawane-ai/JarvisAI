package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.util.SettingsManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JARVIS TTS — Piper is used only for English; Hindi/Marathi always use
 * Hindi, Hinglish and English use Android/Piper voice routing. Android TTS remains the universal fallback.
 */
class TextToSpeechHelper(context: Context) {

    private val app = context.applicationContext
    private val settings = SettingsManager(app)
    private val piper = PiperTtsEngine(app)

    private var androidTts: TextToSpeech? = null
    private val androidReady = AtomicBoolean(false)
    private var pending: String? = null

    init {
        androidTts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidReady.set(true)
                configureAndroidVoice()
                pending?.let { t ->
                    pending = null
                    speak(t)
                }
                Log.i(TAG, "Android TTS ready")
            } else {
                Log.e(TAG, "Android TTS init failed: $status")
            }
        }
        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Android TTS utterance error")
            }
        })
        if (piper.isReady) Log.i(TAG, "Piper available for English replies")
        else Log.w(TAG, "Piper unavailable — Android TTS will handle all languages")
    }

    fun speak(text: String) {
        val cleaned = text
            .replace("...", ", ")
            .replace(" — ", ", ")
            .replace(" – ", ", ")
            .trim()
        if (cleaned.isEmpty()) return

        val replyLocale = ReplyLanguageDetector.detect(cleaned)
        // The bundled Piper model is English-only. Never let it read Hindi or
        // Hindi/Hinglish text with an English phoneme model, even if Piper is installed.
        if (replyLocale.language == "en" && piper.isReady) {
            try {
                piper.stop()
                if (piper.speak(cleaned)) {
                    Log.i(TAG, "Spoke via Piper locale=$replyLocale")
                    return
                }
                Log.w(TAG, "Piper speak failed — falling back to Android TTS")
            } catch (e: Exception) {
                Log.e(TAG, "Piper error: ${e.message}")
            }
        } else if (replyLocale.language != "en") {
            Log.i(TAG, "Bypassing English-only Piper for locale=$replyLocale")
        }

        speakAndroid(cleaned)
    }

    private fun speakAndroid(text: String) {
        val tts = androidTts
        if (tts == null || !androidReady.get()) {
            pending = text
            Log.w(TAG, "Android TTS not ready — queued")
            return
        }
        try {
            configureAndroidVoice(text)
            tts.stop()
            val id = UUID.randomUUID().toString()
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r == TextToSpeech.ERROR) Log.e(TAG, "Android speak ERROR")
            else Log.i(TAG, "Spoke via Android TTS locale=${ReplyLanguageDetector.detect(text)}")
        } catch (e: Exception) {
            Log.e(TAG, "Android speak failed: ${e.message}")
        }
    }

    private fun configureAndroidVoice(text: String = "") {
        val tts = androidTts ?: return
        try {
            val requestedLocale = ReplyLanguageDetector.detect(text)
            val localeCandidates = when (requestedLocale.language) {
                "hi" -> listOf(Locale("hi", "IN"), Locale("en", "IN"))
                else -> listOf(Locale("en", "IN"), Locale.UK, Locale.US)
            }
            // Respect the Android engine's currently selected default voice.
            // The previous quality-based fallback could silently select a male voice.
            // Only set the requested language; do not replace the user's default voice.
            val result = tts.setLanguage(requestedLocale)
            Log.i(TAG, "Using Android default voice for locale=$requestedLocale result=$result")
            tts.setSpeechRate(settings.getVoiceSpeed().coerceIn(0.7f, 1.4f))
            tts.setPitch(settings.getVoicePitch().coerceIn(0.8f, 1.2f))
        } catch (e: Exception) {
            Log.w(TAG, "configureAndroidVoice: ${e.message}")
        }
    }

    private fun voiceNameSuggestsFeminine(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf("female", "woman", "feminine").any { lower.contains(it) }
    }

    fun stop() {
        try { piper.stop() } catch (_: Exception) {}
        try { androidTts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { piper.release() } catch (_: Exception) {}
        try {
            androidTts?.shutdown()
        } catch (_: Exception) {}
        androidTts = null
        androidReady.set(false)
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
