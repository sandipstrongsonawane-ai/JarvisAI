package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Offline neural speaker verification backed by sherpa-onnx 3D-Speaker CAM++.
 * Audio and embeddings stay on-device. The random spoken challenge is a basic
 * replay deterrent, not a guarantee against live relay or high-quality synthesis.
 */
class VoiceAuthManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_voice_auth", Context.MODE_PRIVATE)
    private val sampleRate = 16000
    private val modelName = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    private val threshold = 0.60f

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean("enabled", enabled).apply()
    fun isEnrolled(): Boolean = readEmbedding() != null

    fun resetEnrollment() {
        prefs.edit().remove("voice_embedding").remove("voiceprint").putBoolean("enabled", false).apply()
    }

    fun captureSample(durationMs: Int = 2200): ShortArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, sampleRate)
            )
        } catch (_: Exception) {
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val required = (sampleRate.toLong() * durationMs / 1000L).toInt()
        val output = ShortArray(required)
        var offset = 0
        var started = false
        try {
            recorder.startRecording()
            started = true
            while (offset < required) {
                val count = recorder.read(output, offset, required - offset)
                if (count <= 0) break
                offset += count
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { if (started) recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
        return if (offset >= sampleRate * 12 / 10) output.copyOf(offset) else null
    }

    suspend fun enrollFromSamples(samples: List<ShortArray>): Boolean = withContext(Dispatchers.Default) {
        if (samples.size < 2 || samples.any { it.size < sampleRate * 12 / 10 }) return@withContext false
        val vectors = samples.map { embedding(it) ?: return@withContext false }
        val dimension = vectors.first().size
        if (dimension <= 0 || vectors.any { it.size != dimension }) return@withContext false
        val average = FloatArray(dimension)
        vectors.forEach { vector -> for (i in vector.indices) average[i] += vector[i] }
        for (i in average.indices) average[i] /= vectors.size.toFloat()
        val normalized = normalize(average) ?: return@withContext false
        prefs.edit().putString("voice_embedding", normalized.joinToString(",")).apply()
        true
    }

    suspend fun verify(samples: ShortArray): Boolean = withContext(Dispatchers.Default) {
        if (samples.size < sampleRate * 12 / 10) return@withContext false
        val enrolled = readEmbedding() ?: return@withContext false
        val candidate = embedding(samples) ?: return@withContext false
        if (candidate.size != enrolled.size) return@withContext false
        cosineSimilarity(enrolled, candidate) >= threshold
    }

    private fun readEmbedding(): FloatArray? {
        val stored = prefs.getString("voice_embedding", null) ?: return null
        val parts = stored.split(",")
        if (parts.size !in 32..4096) return null
        val vector = parts.map { it.toFloatOrNull() ?: return null }.toFloatArray()
        if (vector.any { !it.isFinite() }) return null
        return normalize(vector)
    }

    private fun embedding(samples: ShortArray): FloatArray? {
        val extractor = try { getExtractor() } catch (_: Throwable) { return null }
        val stream = try { extractor.createStream() } catch (_: Throwable) { return null }
        return try {
            val floats = FloatArray(samples.size) { samples[it] / 32768.0f }
            stream.acceptWaveform(floats, sampleRate)
            stream.inputFinished()
            val vector = extractor.compute(stream)
            if (vector.isEmpty() || vector.any { !it.isFinite() }) null else normalize(vector)
        } catch (_: Throwable) {
            null
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    private fun getExtractor(): SpeakerEmbeddingExtractor {
        synchronized(extractorLock) {
            extractor?.let { return it }
            val created = SpeakerEmbeddingExtractor(
                assetManager = context.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = modelName,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            extractor = created
            return created
        }
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it.toDouble() }
        if (sum <= 1e-12) return null
        val norm = sqrt(sum).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }

    companion object {
        private val extractorLock = Any()
        @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    }
}
