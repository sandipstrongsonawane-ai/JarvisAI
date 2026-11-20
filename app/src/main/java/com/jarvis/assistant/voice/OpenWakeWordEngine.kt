package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class OpenWakeWordEngine(private val context: Context) {

    private var engine: WakeWordEngine? = null
    private var collectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopping = false

    fun start(onWake: () -> Unit, onFailure: (Throwable) -> Unit = {}): Boolean {
        return try {
            stop()
            stopping = false
            val failureReported = AtomicBoolean(false)
            fun reportFailure(error: Throwable) {
                if (!stopping && failureReported.compareAndSet(false, true)) {
                    Log.e(TAG, "wake audio/detection stream failed", error)
                    onFailure(error)
                }
            }
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
            eng.start()
            collectJob = scope.launch {
                eng.detections
                    .catch { e -> reportFailure(e) }
                    .onCompletion { cause ->
                        if (cause != null) reportFailure(cause)
                        else if (!stopping) reportFailure(
                            IllegalStateException("Wake detection stream completed unexpectedly")
                        )
                    }
                    .collect { det ->
                        Log.i(TAG, "wake detected: " + det.model.name + " score=" + det.score)
                        onWake()
                    }
            }
            Log.i(TAG, "started — say Hey Jarvis")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "start failed: " + e.message, e)
            false
        }
    }

    fun stop() {
        stopping = true
        try { collectJob?.cancel() } catch (_: Exception) {}
        collectJob = null
        try { engine?.stop() } catch (_: Exception) {}
        try { engine?.release() } catch (_: Exception) {}
        engine = null
    }

    companion object {
        private const val TAG = "OpenWakeWord"
    }
}
