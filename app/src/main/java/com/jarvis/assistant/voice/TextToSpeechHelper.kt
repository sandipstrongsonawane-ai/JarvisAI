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
 * JARVIS TTS — Piper first, Android system TTS always as fallback.
 * Never goes fully mute if the device has any TTS engine.
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
                Log.i(TAG, "Android TTS ready (fallback)")
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
        if (piper.isReady) Log.i(TAG, "Piper available")
        else Log.w(TAG, "Piper not ready — using Android TTS fallback")
    }

    fun speak(text: String) {
        val cleaned = text
            .replace("...", ", ")
            .replace(" — ", ", ")
            .replace(" – ", ", ")
            .trim()
        if (cleaned.isEmpty()) return

        // Prefer Piper when native stack is ready
        if (piper.isReady) {
            try {
                piper.stop()
                val ok = piper.speak(cleaned)
                if (ok) {
                    Log.i(TAG, "Spoke via Piper")
                    return
                }
                Log.w(TAG, "Piper speak failed — falling back to Android TTS")
            } catch (e: Exception) {
                Log.e(TAG, "Piper error: ${e.message}")
            }
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
            configureAndroidVoice()
            tts.stop()
            val id = UUID.randomUUID().toString()
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r == TextToSpeech.ERROR) Log.e(TAG, "Android speak ERROR")
            else Log.i(TAG, "Spoke via Android TTS")
        } catch (e: Exception) {
            Log.e(TAG, "Android speak failed: ${e.message}")
        }
    }

    private fun configureAndroidVoice(text: String = "") {
        val tts = androidTts ?: return
        try {
            val requestedLocale = detectReplyLocale(text)
            val available = try { tts.voices } catch (_: Exception) { null }
            val localeCandidates = when (requestedLocale.language) {
                "mr" -> listOf(Locale("mr", "IN"), Locale("hi", "IN"), Locale("en", "IN"))
                "hi" -> listOf(Locale("hi", "IN"), Locale("mr", "IN"), Locale("en", "IN"))
                else -> listOf(Locale("en", "IN"), Locale.UK, Locale.US)
            }
            var chosen = false
            if (available != null) {
                for (locale in localeCandidates) {
                    val exact = available.filter { it.locale.language == locale.language &&
                        (it.locale.country.isEmpty() || it.locale.country == locale.country) }
                    if (exact.isEmpty()) continue
                    // Prefer a voice with a feminine/warm name hint if the engine exposes one;
                    // otherwise use the highest-quality matching locale voice.
                    val preferred = exact.firstOrNull { voiceNameSuggestsFeminine(it.name) }
                        ?: exact.maxByOrNull { it.quality }
                    if (preferred != null) {
                        tts.voice = preferred
                        chosen = true
                        Log.i(TAG, "Selected TTS voice locale=${preferred.locale} name=${preferred.name}")
                        break
                    }
                }
            }
            if (!chosen) {
                val fallbackLocale = localeCandidates.firstOrNull { locale ->
                    tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
                } ?: Locale.getDefault()
                tts.language = fallbackLocale
                Log.w(TAG, "No matching voice listed; language fallback=${fallbackLocale}")
            }
            val speed = settings.getVoiceSpeed().coerceIn(0.7f, 1.4f)
            val pitch = settings.getVoicePitch().coerceIn(0.8f, 1.2f)
            tts.setSpeechRate(speed)
            tts.setPitch(pitch)
        } catch (e: Exception) {
            Log.w(TAG, "configureAndroidVoice: ${e.message}")
        }
    }

    private fun detectReplyLocale(text: String): Locale {
        val lower = text.lowercase()
        val marathiHints = listOf(
            "आहे", "आहोत", "नाही", "काय", "म्हणजे", "करूया", "करतो", "करते",
            "बरं", "मस्त", "तुम्ही", "तुझं", "आणि", "पण", "झालं", "चालेल", "होईल"
        )
        val hasDevanagari = text.any { it in 'अ'..'ॿ' }
        val hasMarathiHint = marathiHints.any { lower.contains(it) }
        return when {
            hasMarathiHint -> Locale("mr", "IN")
            hasDevanagari -> Locale("hi", "IN")
            lower.contains("namaskar") || lower.contains("kay") || lower.contains("barobar") -> Locale("mr", "IN")
            else -> Locale("hi", "IN")
        }
    }

    private fun voiceNameSuggestsFeminine(name: String): Boolean {
        val lower = name.lowercase()
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
