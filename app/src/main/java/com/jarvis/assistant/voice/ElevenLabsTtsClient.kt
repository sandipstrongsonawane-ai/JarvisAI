package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloud TTS via ElevenLabs — natural female voice for Jarvis.
 *
 * Add the ElevenLabs API key in Jarvis Settings; it is stored with Android Keystore encryption.
 * Voice ID can be changed in Jarvis Settings → Cloud AI & Voice API Keys.
 *
 * The selected female voice is the default. An explicit custom voice ID can override it.
 */
class ElevenLabsTtsClient(private val context: Context) {

    private val settings = SettingsManager(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private val activeCall = AtomicReference<okhttp3.Call?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestGeneration = AtomicLong(0L)

    val isConfigured: Boolean
        get() = settings.getElevenLabsApiKey().isNotBlank()

    private val apiKey: String
        get() = settings.getElevenLabsApiKey().trim()

    /** Always use the exact female voice ID selected by the user. */
    private val voiceId: String
        get() = settings.getElevenLabsVoiceId()

    /**
     * Synthesize [text] and play. Returns true if audio started successfully.
     * Call from a background coroutine; playback is started on completion.
     */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured || text.isBlank()) return@withContext false
        stop()
        val generation = requestGeneration.get()
        val cleaned = text
            .replace("...", ", ")
            .replace(" — ", ", ")
            .replace(" – ", ", ")
            .trim()
        if (cleaned.isEmpty()) return@withContext false
        // Free tier: keep requests short
        val clipped = if (cleaned.length > 2500) cleaned.take(2500) else cleaned

        try {
            val bodyJson = JSONObject().apply {
                put("text", clipped)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.80)
                    put("style", 0.15)
                    put("use_speaker_boost", true)
                })
            }
            val request = Request.Builder()
                .url("$BASE_URL/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Accept", "audio/mpeg")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val call = client.newCall(request)
            activeCall.set(call)
            if (requestGeneration.get() != generation) {
                activeCall.compareAndSet(call, null)
                call.cancel()
                return@withContext false
            }
            call.execute().use { response ->
                if (call.isCanceled() || requestGeneration.get() != generation) return@withContext false
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(200) ?: ""
                    Log.e(TAG, "ElevenLabs HTTP ${response.code}: $err")
                    return@withContext false
                }
                val bytes = response.body?.bytes() ?: return@withContext false
                if (bytes.isEmpty()) return@withContext false

                if (call.isCanceled() || requestGeneration.get() != generation) {
                    activeCall.compareAndSet(call, null)
                    return@withContext false
                }
                activeCall.compareAndSet(call, null)
                val cache = File(context.cacheDir, "jarvis_tts_el.mp3")
                cache.writeBytes(bytes)

                withContext(Dispatchers.Main) {
                    if (requestGeneration.get() == generation) playFile(cache) else false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs speak failed: ${e.message}", e)
            false
        }
    }

    private fun playFile(file: File): Boolean {
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    try {
                        it.release()
                    } catch (_: Exception) {
                    }
                    if (player === it) player = null
                }
                setOnErrorListener { mp, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (player === mp) player = null
                    true
                }
                prepare()
                start()
            }
            newPlayer.isPlaying
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed: ${e.message}")
            if (player === newPlayer) player = null
            try { newPlayer.reset() } catch (_: Exception) {}
            try { newPlayer.release() } catch (_: Exception) {}
            false
        }
    }

    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun stop() {
        requestGeneration.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
        // MediaPlayer is created and played on main; stop/release it there too.
        val cleanup = Runnable { releasePlayer() }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else mainHandler.post(cleanup)
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        try { current.stop() } catch (_: Exception) {}
        try { current.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ElevenLabsTTS"
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Original selected female voice supplied by the user. */
        const val FEMALE_VOICE_ID = "RABOvaPec1ymXz02oDQi"
        /** Legacy stock male voice ID retained for compatibility only. */
        const val DEFAULT_VOICE_ID = "pNInz6obpgDQGcFmaJgB"
    }
}
