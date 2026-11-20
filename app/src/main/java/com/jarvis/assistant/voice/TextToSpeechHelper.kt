package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.jarvis.assistant.util.SettingsManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Female-first Android TTS. Cloud/API text is spoken through this same path.
 * The bundled Piper model is male-only, so it is intentionally not selected.
 */
class TextToSpeechHelper(context: Context) {
    private val app = context.applicationContext
    private val settings = SettingsManager(app)
    private var androidTts: TextToSpeech? = null
    private val androidReady = AtomicBoolean(false)
    private var pending: String? = null

    init {
        // The user explicitly requested a female default; migrate the old male default.
        if (settings.getVoiceType() != "female") settings.setVoiceType("female")
        androidTts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidReady.set(true)
                configureAndroidVoice()
                pending?.let { queued ->
                    pending = null
                    speak(queued)
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
    }

    fun speak(text: String) {
        val cleaned = text.replace("...", ", ")
            .replace(" — ", ", ")
            .replace(" – ", ", ")
            .trim()
        if (cleaned.isEmpty()) return
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
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) Log.e(TAG, "Android speak ERROR")
            else Log.i(TAG, "Spoke using female-first Android voice locale=${ReplyLanguageDetector.detect(text)}")
        } catch (e: Exception) {
            Log.e(TAG, "Android speak failed: ${e.message}", e)
        }
    }

    private fun configureAndroidVoice(text: String = "") {
        val tts = androidTts ?: return
        try {
            val locale = ReplyLanguageDetector.detect(text)
            val languageResult = tts.setLanguage(locale)
            val voices = tts.voices.orEmpty()
            val femaleVoice = voices
                .filter { it.locale.language.equals(locale.language, ignoreCase = true) }
                .filter { voiceNameSuggestsFeminine(it.name) }
                .maxByOrNull { it.quality }

            if (femaleVoice != null) {
                tts.voice = femaleVoice
                Log.i(TAG, "Selected female TTS voice=${femaleVoice.name} locale=${femaleVoice.locale}")
            } else {
                Log.w(TAG, "No identifiable female voice installed for locale=$locale; retaining engine default (languageResult=$languageResult)")
            }
            tts.setSpeechRate(settings.getVoiceSpeed().coerceIn(0.7f, 1.4f))
            tts.setPitch(settings.getVoicePitch().coerceIn(0.8f, 1.2f))
        } catch (e: Exception) {
            Log.w(TAG, "configureAndroidVoice: ${e.message}", e)
        }
    }

    private fun voiceNameSuggestsFeminine(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(
            "female", "woman", "feminine", "samantha", "zira", "jenny",
            "aria", "sara", "karen", "moira", "tessa", "susan", "veena",
            "lekha", "neerja", "hi-in-x-hia", "en-in-x-ene", "en-in-x-ena",
            "en-us-x-sfg", "en-us-x-tpf"
        ).any { lower.contains(it) }
    }

    fun stop() {
        try { androidTts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { androidTts?.shutdown() } catch (_: Exception) {}
        androidTts = null
        androidReady.set(false)
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
