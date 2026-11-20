package com.jarvis.assistant.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the OpenWakeWord engine and verifies that its microphone/inference
 * pipeline is actually producing score heartbeats before reporting LISTENING.
 *
 * WakeWordEngine.start() launches its own recording coroutine and returns
 * immediately. Errors in that internal coroutine do not complete detections,
 * because detections is a separate hot SharedFlow. The score heartbeat is
 * therefore the health signal used to detect a dead audio pipeline.
 */
class OpenWakeWordEngine(private val context: Context) {

    private var engine: WakeWordEngine? = null
    private var detectionJob: Job? = null
    private var scoreJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopping = true
    @Volatile private var lastScoreAtMs = 0L

    fun start(
        onWake: () -> Unit,
        onFailure: (Throwable) -> Unit = {},
        onReady: () -> Unit = {}
    ): Boolean {
        stop()
        stopping = false
        lastScoreAtMs = 0L
        val failureReported = AtomicBoolean(false)
        val readyReported = AtomicBoolean(false)

        fun reportFailure(error: Throwable) {
            if (!stopping && failureReported.compareAndSet(false, true)) {
                Log.e(TAG, "wake audio/inference heartbeat failed", error)
                onFailure(error)
            }
        }

        return try {
            val models = listOf(
                WakeWordModel(
                    name = "Hey Jarvis",
                    modelPath = "hey_jarvis.onnx",
                    threshold = 0.10f
                )
            )
            val eng = WakeWordEngine(
                context = context.applicationContext,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 1500L
            )
            engine = eng

            // Subscribe before start(): detections/scores are hot SharedFlows.
            scoreJob = scope.launch {
                eng.scores.collect {
                    lastScoreAtMs = SystemClock.elapsedRealtime()
                    if (readyReported.compareAndSet(false, true) && !stopping) {
                        Log.i(TAG, "microphone and wake inference pipeline are producing scores")
                        onReady()
                    }
                }
            }
            detectionJob = scope.launch {
                eng.detections.collect { detection ->
                    if (!stopping) {
                        Log.i(
                            TAG,
                            "wake detected: ${detection.model.name} score=${detection.score}"
                        )
                        onWake()
                    }
                }
            }

            // start() only schedules the library's private recording coroutine.
            // It does not prove that AudioRecord initialized or is still alive.
            eng.start()
            watchdogJob = scope.launch {
                delay(INITIAL_AUDIO_TIMEOUT_MS)
                while (isActive && !stopping) {
                    val last = lastScoreAtMs
                    val now = SystemClock.elapsedRealtime()
                    if (last == 0L) {
                        reportFailure(
                            IllegalStateException(
                                "No OpenWakeWord score heartbeat; microphone recording did not start"
                            )
                        )
                        break
                    }
                    if (now - last > STALE_SCORE_TIMEOUT_MS) {
                        reportFailure(
                            IllegalStateException(
                                "OpenWakeWord score heartbeat stopped for ${now - last}ms"
                            )
                        )
                        break
                    }
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }

            Log.i(TAG, "OpenWakeWord start requested — waiting for first audio score")
            true
        } catch (e: Throwable) {
            stopping = true
            cancelJobs()
            try { engine?.stop() } catch (_: Exception) {}
            try { engine?.release() } catch (_: Exception) {}
            engine = null
            Log.e(TAG, "start failed: ${e.message}", e)
            false
        }
    }

    fun stop() {
        stopping = true
        cancelJobs()
        try { engine?.stop() } catch (_: Exception) {}
        try { engine?.release() } catch (_: Exception) {}
        engine = null
        lastScoreAtMs = 0L
    }

    private fun cancelJobs() {
        try { watchdogJob?.cancel() } catch (_: Exception) {}
        try { scoreJob?.cancel() } catch (_: Exception) {}
        try { detectionJob?.cancel() } catch (_: Exception) {}
        watchdogJob = null
        scoreJob = null
        detectionJob = null
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        private const val INITIAL_AUDIO_TIMEOUT_MS = 8_000L
        private const val STALE_SCORE_TIMEOUT_MS = 3_000L
        private const val WATCHDOG_INTERVAL_MS = 1_000L
    }
}
