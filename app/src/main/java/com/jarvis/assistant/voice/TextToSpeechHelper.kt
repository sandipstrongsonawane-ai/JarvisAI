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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Female-first Android TTS. Cloud/API text is spoken through this same path.
 * The bundled Piper model is male-only, so it is intentionally not selected.
 */
class TextToSpeechHelper(context: Context) {
    private val app = context.applicationContext
    private val settings = SettingsManager(app)
    private var androidTts: TextToSpeech? = null
    private val androidReady = AtomicBoolean(false)
    private val androidSpeechActive = AtomicBoolean(false)
    @Volatile private var activeAndroidUtteranceId: String? = null
    private val cloudRequestActive = AtomicBoolean(false)
    private val speechGeneration = AtomicInteger(0)
    private val cloudTts = ElevenLabsTtsClient(app)
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeAndroidUtteranceId) androidSpeechActive.set(true)
            }
            override fun onDone(utteranceId: String?) {
                finishAndroidUtterance(utteranceId)
            }
            override fun onError(utteranceId: String?) {
                finishAndroidUtterance(utteranceId)
                Log.e(TAG, "Android TTS utterance error")
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishAndroidUtterance(utteranceId)
            }
        })
    }

    fun speak(text: String) {
        val cleaned = text.replace("...", ", ")
            .replace(" — ", ", ")
            .replace(" – ", ", ")
            .trim()
        if (cleaned.isEmpty()) return
        val generation = speechGeneration.incrementAndGet()
        if (settings.getPreferCloudVoice() && cloudTts.isConfigured) {
            cloudRequestActive.set(true)
            speechScope.launch {
                try {
                    // Cancel any older cloud request before starting this utterance.
                    cloudTts.stop()
                    stopAndroidSpeech()
                    val started = cloudTts.speak(cleaned)
                    if (!started && generation == speechGeneration.get()) {
                        Log.w(TAG, "ElevenLabs unavailable; falling back to installed Android female voice")
                        speakAndroid(cleaned)
                    }
                } finally {
                    if (generation == speechGeneration.get()) cloudRequestActive.set(false)
                }
            }
        } else {
            cloudTts.stop()
            cloudRequestActive.set(false)
            speakAndroid(cleaned)
        }
    }

    private fun speakAndroid(text: String) {
        val tts = androidTts
        if (tts == null || !androidReady.get()) {
            pending = text
            Log.w(TAG, "Android TTS not ready — queued")
            return
        }
        try {
            stopAndroidSpeech()
            if (!configureAndroidVoice(text)) {
                Log.e(TAG, "No female Android voice is installed; refusing male/default voice fallback")
                return
            }
            val id = UUID.randomUUID().toString()
            activeAndroidUtteranceId = id
            androidSpeechActive.set(true)
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) {
                finishAndroidUtterance(id)
                Log.e(TAG, "Android speak ERROR")
            } else {
                Log.i(TAG, "Spoke using female-first Android voice locale=${ReplyLanguageDetector.detect(text)}")
            }
        } catch (e: Exception) {
            activeAndroidUtteranceId = null
            androidSpeechActive.set(false)
            Log.e(TAG, "Android speak failed: ${e.message}", e)
        }
    }

    private fun finishAndroidUtterance(utteranceId: String?) {
        if (utteranceId != null && utteranceId == activeAndroidUtteranceId) {
            activeAndroidUtteranceId = null
            androidSpeechActive.set(false)
        }
    }

    private fun stopAndroidSpeech() {
        activeAndroidUtteranceId = null
        androidSpeechActive.set(false)
        try { androidTts?.stop() } catch (_: Exception) {}
    }

    private fun configureAndroidVoice(text: String = ""): Boolean {
        val tts = androidTts ?: return false
        return try {
            val locale = ReplyLanguageDetector.detect(text)
            val languageResult = tts.setLanguage(locale)
            val voices = tts.voices.orEmpty()
            val femaleVoice = voices
                .filter { voiceNameSuggestsFeminine(it.name) }
                .filter { it.locale.language.equals(locale.language, ignoreCase = true) }
                .maxByOrNull { it.quality }
                ?: voices
                    .filter { voiceNameSuggestsFeminine(it.name) }
                    .filter { it.locale.language.equals("en", ignoreCase = true) }
                    .maxByOrNull { it.quality }

            if (femaleVoice == null) {
                Log.w(TAG, "No female Android voice installed for locale=$locale; languageResult=$languageResult")
                false
            } else {
                tts.voice = femaleVoice
                tts.setLanguage(femaleVoice.locale)
                Log.i(TAG, "Selected female TTS voice=${femaleVoice.name} locale=${femaleVoice.locale}")
                tts.setSpeechRate(settings.getVoiceSpeed().coerceIn(0.7f, 1.4f))
                tts.setPitch(settings.getVoicePitch().coerceIn(0.8f, 1.2f))
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "configureAndroidVoice: ${e.message}", e)
            false
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

    fun isSpeaking(): Boolean =
        cloudRequestActive.get() ||
            cloudTts.isPlaying() ||
            androidSpeechActive.get() ||
            pending != null

    fun stop() {
        speechGeneration.incrementAndGet()
        pending = null
        cloudRequestActive.set(false)
        androidSpeechActive.set(false)
        try { cloudTts.stop() } catch (_: Exception) {}
        stopAndroidSpeech()
    }

    fun shutdown() {
        stop()
        speechScope.cancel()
        try { androidTts?.shutdown() } catch (_: Exception) {}
        androidTts = null
        androidReady.set(false)
    }

    companion object {
        private const val TAG = "JarvisTTS"
    }
}
