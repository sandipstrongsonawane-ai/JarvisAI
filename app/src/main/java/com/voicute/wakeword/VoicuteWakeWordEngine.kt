package com.voicute.wakeword

import android.content.Context
import android.util.Log

/**
 * Service adapter for Voicute's upstream ONNX wake-word runtime.
 * Audio capture, inference, and anti-false-trigger gates run locally.
 */
class VoicuteWakeWordEngine(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var running = false
    private var engine: WakeWordEngine? = null
    private var capture: AudioCapture? = null
    private var inferenceThread: Thread? = null
    private var detection: DetectionLogic? = null

    @Synchronized
    fun start(onWake: () -> Unit): Boolean {
        stop()
        val activeEngine = engine ?: WakeWordEngine(appContext).also { engine = it }
        if (!activeEngine.isLoaded) {
            Log.e(TAG, "Voicute model load failed: ${activeEngine.errorMessage}")
            try { activeEngine.close() } catch (_: Exception) {}
            engine = null
            return false
        }

        val activeCapture = AudioCapture()
        activeCapture.setListener { message -> Log.e(TAG, "Voicute microphone error: $message") }
        val gates = DetectionLogic()
        capture = activeCapture
        detection = gates
        activeCapture.start()
        running = true

        inferenceThread = Thread({
            try {
                Thread.sleep(500L)
                while (running && !Thread.currentThread().isInterrupted) {
                    val startedAt = System.currentTimeMillis()
                    val chunk = activeCapture.readChunk(
                        activeCapture.ringPos,
                        activeEngine.audioSamplesNeeded
                    )
                    var sumSquares = 0.0
                    for (sample in chunk) sumSquares += sample.toDouble() * sample.toDouble()
                    val rms = kotlin.math.sqrt(sumSquares / chunk.size).toFloat()

                    var result: WakeWordEngine.DetectionResult? = null
                    if (rms >= RMS_GATE) result = activeEngine.process(chunk)

                    val now = System.currentTimeMillis()
                    val word = result?.wakeWord ?: ""
                    val probability = result?.probability ?: 0f
                    val consecutiveFrames = result?.recommendedConsFrames ?: DEFAULT_CONSECUTIVE_FRAMES

                    gates.record(probability, word, rms, now)
                    val detected = gates.evaluate(
                        word,
                        probability,
                        rms,
                        THRESHOLD,
                        consecutiveFrames,
                        0,
                        now
                    )
                    if (detected != null) {
                        Log.i(TAG, "Voicute wake detected: $detected score=$probability rms=$rms")
                        running = false
                        onWake()
                        break
                    }

                    val elapsed = System.currentTimeMillis() - startedAt
                    val sleep = INFERENCE_INTERVAL_MS - elapsed
                    if (sleep > 0) Thread.sleep(sleep)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Log.e(TAG, "Voicute inference loop failed", error)
            }
        }, "VoicuteInference").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "Voicute engine active for Hey Jarvis; threshold=$THRESHOLD L1-L4 enabled")
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        try { capture?.stop() } catch (e: Exception) { Log.w(TAG, "capture stop: ${e.message}") }
        capture = null
        val thread = inferenceThread
        inferenceThread = null
        try {
            thread?.interrupt()
            if (thread != null && thread !== Thread.currentThread()) thread.join(1500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        detection = null
    }

    @Synchronized
    fun release() {
        stop()
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "engine close: ${e.message}") }
        engine = null
    }

    companion object {
        private const val TAG = "VoicuteWake"
        private const val RMS_GATE = 30f
        private const val THRESHOLD = 0.55f
        private const val DEFAULT_CONSECUTIVE_FRAMES = 3
        private const val INFERENCE_INTERVAL_MS = 30L
    }
}
