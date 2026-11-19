package com.jarvis.assistant.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * On-device ASR for the one-use voice-auth phrase.
 *
 * The same PCM is also passed to VoiceAuthManager.verify(); no second recording
 * is made. This model is English-only because the randomized security phrase is
 * deliberately generated from English words. It does not replace command STT.
 */
class VoiceChallengeRecognizer(context: Context) {
    private val appContext = context.applicationContext

    fun recognize(samples: ShortArray): String? = synchronized(lock) {
        if (samples.size < SAMPLE_RATE * 12 / 10) return@synchronized null
        val activeRecognizer = try {
            getRecognizer()
        } catch (_: Throwable) {
            return@synchronized null
        }
        val stream = try {
            activeRecognizer.createStream()
        } catch (_: Throwable) {
            return@synchronized null
        }
        try {
            val waveform = FloatArray(samples.size) { samples[it] / 32768.0f }
            stream.acceptWaveform(waveform, SAMPLE_RATE)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    private fun getRecognizer(): OfflineRecognizer {
        cachedRecognizer?.let { return it }
        val modelDir = "voice_challenge"
        val created = OfflineRecognizer(
            assetManager = appContext.assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx"
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "transducer",
                    numThreads = 2,
                    provider = "cpu"
                ),
                decodingMethod = "greedy_search"
            )
        )
        cachedRecognizer = created
        return created
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private val lock = Any()
        @Volatile private var cachedRecognizer: OfflineRecognizer? = null
    }
}
