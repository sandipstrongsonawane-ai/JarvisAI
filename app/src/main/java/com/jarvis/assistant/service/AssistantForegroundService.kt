package com.jarvis.assistant.service


import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.GeminiClient
import com.jarvis.assistant.brain.BrainState
import com.jarvis.assistant.brain.OfflineBrain
import com.jarvis.assistant.executor.CommandExecutor
import com.jarvis.assistant.executor.RiskConfirmGate
import com.jarvis.assistant.network.PcBridgeServer
import com.jarvis.assistant.util.FileFinder
import com.jarvis.assistant.util.LocationHelper
import com.jarvis.assistant.util.NetworkStatusManager
import com.jarvis.assistant.util.WeatherClient
import com.jarvis.assistant.util.PersistentMemory
import com.jarvis.assistant.util.SettingsManager
import com.jarvis.assistant.voice.SpeechToText
import com.jarvis.assistant.voice.TextToSpeechHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.Calendar
import com.jarvis.assistant.ai.JarvisLlmClient

/**
 * Always-alive foreground service. The on-device wake detector calls
 * startListeningCycle() on wake, rather than this service polling continuously —
 * continuous SpeechRecognizer usage will drain battery fast and Android will kill it.
 *
 * Command routing is offline-first: OfflineBrain tries to answer locally (app
 * control, settings, time/date/battery, math, notes, small talk — all on-device).
 * Gemini is only ever contacted when the offline brain doesn't recognize the
 * command AND NetworkStatusManager confirms there's an actual internet path.
 */
class AssistantForegroundService : Service() {

    private lateinit var stt: SpeechToText
    private var callAnnouncer: com.jarvis.assistant.util.IncomingCallAnnouncer? = null
    private lateinit var tts: TextToSpeechHelper
    private lateinit var executor: CommandExecutor
    private lateinit var gemini: GeminiClient
    private lateinit var offlineBrain: OfflineBrain
    private lateinit var networkStatus: NetworkStatusManager
    private lateinit var weatherClient: WeatherClient
    private lateinit var locationHelper: LocationHelper
    private lateinit var pcBridge: PcBridgeServer
    private lateinit var voiceAuth: com.jarvis.assistant.voice.VoiceAuthManager
    private var voicuteWake: com.voicute.wakeword.VoicuteWakeWordEngine? = null
    private var voiceSessionVerified = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var proactive: com.jarvis.assistant.util.ProactiveAlertManager? = null
    private val proactiveTick = object : Runnable {
        override fun run() {
            proactive?.tick()
            mainHandler.postDelayed(this, 5 * 60_000L)
        }
    }
    var listener: AssistantListener? = null
    private var lastListenCycleMs = 0L
    private var listenCycleRunning = false
    @Volatile private var externalMicSessionActive = false
    private var wakeRetryAttempt = 0
    private var wakeRetryPosted = false
    private var wakeStartGeneration = 0
    // Invalidates delayed mic-release/rearm callbacks when a newer session takes ownership.
    private var wakeResumeGeneration = 0
    @Volatile private var wakeStartInProgress = false
    private var wakeCpuLock: android.os.PowerManager.WakeLock? = null
    private val wakeRetryRunnable = Runnable {
        wakeRetryPosted = false
        if (!externalMicSessionActive && !listenCycleRunning && !wakeStartInProgress) startWakeWordListening()
    }

    interface AssistantListener {
        fun onStateChanged(state: BrainState)
        fun onTranscript(text: String)
        fun onResponse(text: String, fromCloud: Boolean)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 101
        const val ACTION_EXECUTE_DEFERRED_COMMAND = "com.jarvis.assistant.EXECUTE_DEFERRED_COMMAND"
        const val ACTION_PAUSE_WAKE_FOR_EXTERNAL_MIC = "com.jarvis.assistant.PAUSE_WAKE_FOR_EXTERNAL_MIC"
        const val ACTION_RESUME_WAKE_AFTER_EXTERNAL_MIC = "com.jarvis.assistant.RESUME_WAKE_AFTER_EXTERNAL_MIC"
    }

    override fun onCreate() {
        super.onCreate()
        stt = SpeechToText(this)
        // Hard stop any residual continuous STT from previous process death
        try { stt.stopContinuous() } catch (_: Exception) {}
        tts = TextToSpeechHelper(this)
        callAnnouncer = com.jarvis.assistant.util.IncomingCallAnnouncer(this) { tts }
        try { callAnnouncer?.start() } catch (_: Exception) {}
        executor = CommandExecutor(this)
        gemini = GeminiClient(apiKeyProvider = { SettingsManager(this).getGeminiApiKey() })
        networkStatus = NetworkStatusManager(this)
        weatherClient = WeatherClient(SettingsManager(this).getOpenWeatherApiKey())
        locationHelper = LocationHelper(this)
        pcBridge = PcBridgeServer(this) { speech -> processSpeech(speech) }
        voiceAuth = com.jarvis.assistant.voice.VoiceAuthManager(this)
        voicuteWake = com.voicute.wakeword.VoicuteWakeWordEngine(applicationContext)
        offlineBrain = OfflineBrain(this, executor) { turnOn ->
            if (turnOn) pcBridge.start() else pcBridge.stop()
        }
        createNotificationChannel()
        scheduleDailySummary()
        proactive = com.jarvis.assistant.util.ProactiveAlertManager(this) { msg ->
            mainHandler.post {
                try { try { com.jarvis.assistant.ui.HudController.speaking() } catch (_: Exception) {}
                    tts.speak(msg) } catch (_: Exception) {}
            }
        }
        proactive?.start()
        mainHandler.postDelayed(proactiveTick, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("JarvisService", "Microphone permission missing; refusing microphone foreground service start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("J.A.R.V.I.S. online — say Hey Jarvis"))
        when (intent?.action) {
            ACTION_PAUSE_WAKE_FOR_EXTERNAL_MIC -> {
                externalMicSessionActive = true
                wakeResumeGeneration++
                wakeStartGeneration++
                listenCycleRunning = false
                releaseAllMics()
                releaseWakeCpuLock()
                try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                android.util.Log.i("JarvisService", "Wake microphone paused for an external capture session")
                return START_STICKY
            }
            ACTION_RESUME_WAKE_AFTER_EXTERNAL_MIC -> {
                externalMicSessionActive = false
                wakeStartGeneration++
                wakeRetryAttempt = 0
                wakeRetryPosted = false
                mainHandler.removeCallbacks(wakeRetryRunnable)
                android.util.Log.i("JarvisService", "Wake microphone resume requested after external capture session")
                // AudioRecord release can lag behind the enrollment Activity/TTS teardown.
                mainHandler.postDelayed({ startWakeWordListening() }, 700L)
                return START_STICKY
            }
        }
        if (intent?.action == ACTION_EXECUTE_DEFERRED_COMMAND) {
            releaseAllMics()
            val action = intent.getStringExtra(com.jarvis.assistant.RuntimePermissionActivity.EXTRA_ACTION).orEmpty()
            val target = intent.getStringExtra(com.jarvis.assistant.RuntimePermissionActivity.EXTRA_TARGET)
            val message = intent.getStringExtra(com.jarvis.assistant.RuntimePermissionActivity.EXTRA_MESSAGE)
            val commandJson = intent.getStringExtra(com.jarvis.assistant.RuntimePermissionActivity.EXTRA_COMMAND_JSON)
            scope.launch {
                val command = commandJson?.let { raw ->
                    try { commandFromJson(org.json.JSONObject(raw)) } catch (_: Exception) { null }
                } ?: com.jarvis.assistant.model.AssistantCommand(action, target, message)
                val result = try {
                    executeAssistantCommand(command, confirmed = true)
                } catch (e: Exception) {
                    android.util.Log.e("JarvisService", "Deferred action failed", e)
                    "The action failed after permission was granted: ${e.message ?: "unknown error"}."
                }
                listener?.onStateChanged(BrainState.SPEAKING)
                listener?.onResponse(result, false)
                tts.speak(result)
                resumeWakeSoon(2500L)
            }
            return START_STICKY
        }
        // Siri-style: on-device KWS only (not continuous Google STT).
        try { stt.stopContinuous() } catch (_: Exception) {}
        startWakeWordListening()
        val action = intent?.action
        if (action == com.jarvis.assistant.widget.JarvisWidgetActionReceiver.ACTION_START_LISTENING) {
            startListeningCycle()
        }
        return START_STICKY
    }


    /** Voicute is the only standby wake engine; STT owns the mic only after detection. */
    private fun releaseAllMics() {
        try { voicuteWake?.stop() } catch (_: Exception) {}
        try { stt.stopContinuous() } catch (_: Exception) {}
    }

    private fun startWakeWordListening() {
        if (externalMicSessionActive) {
            android.util.Log.i("JarvisService", "Voicute wake start blocked: external microphone session active")
            return
        }
        if (listenCycleRunning || wakeStartInProgress) {
            android.util.Log.d("JarvisService", "Voicute wake start deferred: command mic or startup already owns the pipeline")
            return
        }
        val sm = com.jarvis.assistant.util.SettingsManager(this)
        if (!sm.getBackgroundListen() && !sm.getWakeWordEnabled()) {
            try { voicuteWake?.stop() } catch (_: Exception) {}
            releaseWakeCpuLock()
            try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
            android.util.Log.i("JarvisService", "Wake off in settings")
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("JarvisService", "Voicute wake start skipped: microphone permission missing")
            try { voicuteWake?.stop() } catch (_: Exception) {}
            releaseWakeCpuLock()
            return
        }

        holdWakeCpuLock()
        val generation = ++wakeStartGeneration
        try { stt.stopContinuous() } catch (_: Exception) {}
        try { voicuteWake?.stop() } catch (_: Exception) {}
        val detector = voicuteWake
            ?: com.voicute.wakeword.VoicuteWakeWordEngine(applicationContext).also { voicuteWake = it }

        // AudioCapture.start() waits for AudioRecord RECORDSTATE_RECORDING.
        // Run blocking startup off-main and verify it remains the current mic owner.
        wakeStartInProgress = true
        scope.launch(Dispatchers.IO) {
            val started = try {
                detector.start(
                    onWake = {
                        mainHandler.post {
                            if (voicuteWake === detector &&
                                generation == wakeStartGeneration &&
                                !listenCycleRunning &&
                                !externalMicSessionActive
                            ) {
                                wakeRetryAttempt = 0
                                wakeRetryPosted = false
                                mainHandler.removeCallbacks(wakeRetryRunnable)
                                android.util.Log.i("JarvisService", "Hey Jarvis detected by Voicute on-device KWS")
                                try { detector.stop() } catch (_: Exception) {}
                                try { com.jarvis.assistant.ui.HudController.listening() } catch (_: Exception) {}
                                try { com.jarvis.assistant.ui.ActivationEdgeOverlay.show(this@AssistantForegroundService) } catch (_: Exception) {}
                                startListeningCycle()
                            }
                        }
                    },
                    onError = {
                        mainHandler.post {
                            if (voicuteWake === detector && generation == wakeStartGeneration) {
                                wakeStartInProgress = false
                                try { detector.stop() } catch (_: Exception) {}
                                try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                                android.util.Log.e("JarvisService", "Voicute microphone/inference failed; scheduling recovery")
                                scheduleWakeResumeRetry()
                            }
                        }
                    }
                ) && detector.isRunning()
            } catch (error: Throwable) {
                android.util.Log.e("JarvisService", "Voicute wake startup failed", error)
                // Match the native-initialization safety guard: clean up any partially
                // initialized wake resources without allowing cleanup failure to kill the service.
                try {
                    detector.stop()
                } catch (cleanupError: Throwable) {
                    android.util.Log.w("JarvisService", "Voicute wake cleanup after startup failure also failed", cleanupError)
                }
                false
            }
            mainHandler.post {
                if (generation != wakeStartGeneration || externalMicSessionActive || listenCycleRunning) {
                    try { detector.stop() } catch (_: Exception) {}
                    if (voicuteWake === detector) wakeStartInProgress = false
                    return@post
                }
                wakeStartInProgress = false
                if (started) {
                    wakeRetryAttempt = 0
                    wakeRetryPosted = false
                    mainHandler.removeCallbacks(wakeRetryRunnable)
                    android.util.Log.i("JarvisService", "Voicute wake standby active; AudioRecord confirmed ready")
                    try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                    listener?.onStateChanged(BrainState.IDLE)
                } else {
                    try { detector.stop() } catch (_: Exception) {}
                    android.util.Log.e("JarvisService", "Voicute microphone/model unavailable; scheduling retry")
                    scheduleWakeResumeRetry()
                }
            }
        }
    }

    private fun holdWakeCpuLock() {
        try {
            val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val lock = wakeCpuLock ?: power.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VoicuteWake"
            ).also {
                it.setReferenceCounted(false)
                wakeCpuLock = it
            }
            if (!lock.isHeld) {
                lock.acquire()
                android.util.Log.i("JarvisService", "Partial CPU wake lock acquired for always-on Voicute inference")
            }
        } catch (error: Exception) {
            android.util.Log.e("JarvisService", "Could not acquire CPU wake lock; Android may suspend standby inference", error)
        }
    }

    private fun releaseWakeCpuLock() {
        try {
            wakeCpuLock?.let { if (it.isHeld) it.release() }
        } catch (error: Exception) {
            android.util.Log.w("JarvisService", "CPU wake lock release failed", error)
        }
    }

    private fun scheduleWakeResumeRetry() {
        if (externalMicSessionActive || wakeRetryPosted || listenCycleRunning || wakeStartInProgress) return
        val sm = com.jarvis.assistant.util.SettingsManager(this)
        if (!sm.getBackgroundListen() && !sm.getWakeWordEnabled()) return
        val delays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        val delayMs = delays[wakeRetryAttempt.coerceIn(0, delays.lastIndex)]
        wakeRetryAttempt = (wakeRetryAttempt + 1).coerceAtMost(delays.lastIndex)
        wakeRetryPosted = true
        android.util.Log.w("JarvisService", "Scheduling Voicute recovery retry #$wakeRetryAttempt in ${delayMs}ms")
        mainHandler.postDelayed(wakeRetryRunnable, delayMs)
    }

    /** Text chat — same offline-first pipeline as voice. */
    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        handleUserSpeech(trimmed, requireVoiceAuth = false)
    }


    private fun forceReleaseCommandMic() {
        try { stt.stopContinuous() } catch (_: Exception) {}
        try { voicuteWake?.stop() } catch (_: Exception) {}

        listenCycleRunning = false
        try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
    }

    private fun resumeWakeSoon(delayMs: Long = 1800L) {
        val generation = ++wakeResumeGeneration
        mainHandler.postDelayed({
            // A newer manual/STT/external-mic session owns the microphone now.
            if (generation != wakeResumeGeneration || externalMicSessionActive || listenCycleRunning) return@postDelayed
            try {
                // Critical: Google STT must be fully stopped before the wake detector reacquires AudioRecord
                forceReleaseCommandMic()
                // Small gap so AudioRecord is free
                mainHandler.postDelayed({
                    if (generation != wakeResumeGeneration || externalMicSessionActive || listenCycleRunning) return@postDelayed
                    try {
                        startWakeWordListening()
                        android.util.Log.i("JarvisService", "Hey Jarvis wake armed")
                    } catch (e: Exception) {
                        android.util.Log.w("JarvisService", "resume wake: " + e.message)
                    }
                }, 350L)
            } catch (e: Exception) {
                android.util.Log.w("JarvisService", "resume wake: " + e.message)
            }
        }, delayMs)
    }





    private fun startFollowUpListen() {
        if (externalMicSessionActive) return
        wakeResumeGeneration++
        mainHandler.postDelayed({
            if (externalMicSessionActive) return@postDelayed
            if (listenCycleRunning) return@postDelayed

            try { voicuteWake?.stop() } catch (_: Exception) {}
            try { stt.stopContinuous() } catch (_: Exception) {}
            listenCycleRunning = true
            lastListenCycleMs = System.currentTimeMillis()
            try { com.jarvis.assistant.ui.HudController.listening() } catch (_: Exception) {}
            try { com.jarvis.assistant.ui.ActivationEdgeOverlay.show(this@AssistantForegroundService) } catch (_: Exception) {}
            listener?.onStateChanged(BrainState.LISTENING)
            try {
                stt.listenOnce(
                    onResult = { speech ->
                        listenCycleRunning = false
                        try { stt.stopContinuous() } catch (_: Exception) {}
                        val s = speech.trim()
                        val cancel = s.equals("stop", true) || s.equals("cancel", true) ||
                            s.equals("never mind", true) || s.equals("nevermind", true) ||
                            s.equals("bas", true) || s.equals("cancel karo", true)
                        if (s.isNotBlank() && !cancel) {
                            handleUserSpeech(s)
                        } else {
                            try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                            try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
                            listener?.onStateChanged(BrainState.IDLE)
                            resumeWakeSoon(600L)
                        }
                    },
                    onError = {
                        listenCycleRunning = false
                        try { stt.stopContinuous() } catch (_: Exception) {}
                        try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                        try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
                        listener?.onStateChanged(BrainState.IDLE)
                        resumeWakeSoon(600L)
                    }
                )
            } catch (_: Exception) {
                listenCycleRunning = false
                resumeWakeSoon(600L)
            }
        }, 2200L) // after reply TTS starts
    }

    fun startListeningCycle() {
        if (externalMicSessionActive) {
            android.util.Log.i("JarvisService", "Listening cycle blocked: external microphone session active")
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("JarvisService", "Listening cycle blocked: microphone permission missing")
            listenCycleRunning = false
            listener?.onStateChanged(BrainState.ERROR)
            return
        }
        wakeResumeGeneration++ // invalidate any stale delayed standby re-arm
        val now = System.currentTimeMillis()
        // Allow re-tap after 1.5s (old 8s gate blocked reactor retries)
        if (listenCycleRunning && now - lastListenCycleMs < 1500L) {
            android.util.Log.d("JarvisService", "listen cycle busy")
            return
        }
        wakeStartGeneration++ // cancel any delayed standby start before taking command mic
        try { voicuteWake?.stop() } catch (_: Exception) {}
        lastListenCycleMs = now
        listenCycleRunning = true

        // A manual tap interrupts any current TTS reply and takes control immediately.
        try { tts.stop() } catch (_: Exception) {}
        // Hold wake engines OFF for entire command window
                        
        try { stt.stopContinuous() } catch (_: Exception) {}
        // Note: do not removeCallbacksAndMessages(null) — that kills proactive timers.

        try { com.jarvis.assistant.ui.HudController.listening() } catch (_: Exception) {}
        try { com.jarvis.assistant.ui.ActivationEdgeOverlay.show(this) } catch (_: Exception) {}
        listener?.onStateChanged(BrainState.LISTENING)
        try {
            android.widget.Toast.makeText(this, "Listening…", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}

        // Start command recognition directly after the wake phrase. No spoken
        // acknowledgement or activation tone should be injected into the mic handoff.

        // Safety timeout (longer session)
        mainHandler.postDelayed({
            if (listenCycleRunning && System.currentTimeMillis() - lastListenCycleMs >= 20000L) {
                listenCycleRunning = false
                try { stt.stopContinuous() } catch (_: Exception) {}
                try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
                listener?.onStateChanged(BrainState.IDLE)
                android.util.Log.w("JarvisService", "listen cycle timed out — reset")
                resumeWakeSoon(800L)
            }
        }, 20000L)

        fun beginStt(retry: Boolean = false) {
            try {
                                        
                stt.listenOnce(
                    onResult = { speech ->
                        listenCycleRunning = false
                        try { stt.stopContinuous() } catch (_: Exception) {}
                        val s = speech.trim()
                        val echo = s.equals("ji sagar", true) ||
                            s.equals("jee sagar", true) ||
                            s.equals("yes sir", true) ||
                            s.equals("yes ma'am", true) ||
                            s.equals("yes mam", true) ||
                            s.equals("yes", true) ||
                            s.equals("listening", true)
                        android.util.Log.i("JarvisService", "STT result: [$s] echo=$echo")
                        if (s.isNotBlank() && !echo) {
                            handleUserSpeech(s)
                        } else {
                            try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                            try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
                            listener?.onStateChanged(BrainState.IDLE)
                            resumeWakeSoon(1200L)
                        }
                    },
                    onError = {
                        // One automatic retry (speech timeout / no-match is common)
                        if (!retry && listenCycleRunning) {
                            android.util.Log.w("JarvisService", "STT error — retry once")
                            mainHandler.postDelayed({ beginStt(retry = true) }, 400L)
                        } else {
                            listenCycleRunning = false
                            try { stt.stopContinuous() } catch (_: Exception) {}
                            try { com.jarvis.assistant.ui.HudController.idle() } catch (_: Exception) {}
                            try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
                            listener?.onStateChanged(BrainState.IDLE)
                            android.util.Log.w("JarvisService", "STT onError final")
                            resumeWakeSoon(1200L)
                        }
                    }
                )
            } catch (e: Exception) {
                listenCycleRunning = false
                android.util.Log.e("JarvisService", "listenOnce failed: ${e.message}")
                listener?.onStateChanged(BrainState.IDLE)
                resumeWakeSoon(1200L)
            }
        }

        // Wake phrase has completed; open one-shot command STT immediately.
        beginStt(false)
    }


    private fun handleUserSpeech(speech: String, requireVoiceAuth: Boolean = true) {
        listener?.onTranscript(speech)
        try { com.jarvis.assistant.ui.ActivationEdgeOverlay.hide() } catch (_: Exception) {}
        listener?.onStateChanged(BrainState.THINKING)

        scope.launch {
            val (resultText, fromCloud) = processSpeech(speech, requireVoiceAuth)

            listener?.onStateChanged(BrainState.SPEAKING)
            listener?.onResponse(resultText, fromCloud)

            // A full code listing read aloud is useless and slow — speak a short line
            // instead when the reply is a code block or unusually long, while the full
            // text still reaches the screen via onResponse() above.
            val speechText = if (resultText.contains("```") || resultText.length > 400) {
                "I've displayed the full details on screen for your review, sir."
            } else {
                resultText
            }
            tts.speak(speechText)
            // Wait for the selected voice (cloud or Android) to finish before
            // reopening the microphone. This prevents TTS echo and clipped replies.
            val speechFinished = withTimeoutOrNull(45_000L) {
                while (tts.isSpeaking()) delay(100L)
                true
            } ?: false
            if (!speechFinished) {
                android.util.Log.w("JarvisService", "TTS completion timed out; stopping playback before listening")
                tts.stop()
            }
            // Preserve the proven #456 post-reply follow-up window.
            // Voicute remains the only standby wake engine; the one-shot STT
            // follow-up closes on result/timeout/error and then re-arms Voicute.
            if (!listenCycleRunning && !externalMicSessionActive) {
                android.util.Log.i("JarvisService", "Reply complete; opening #456-style follow-up window")
                startFollowUpListen()
            }
        }
    }

    /**
     * Shared reasoning pipeline used both by on-device voice input and by the PC bridge
     * (Phase 5). Order: weather (needs network+location, handled specially since it doesn't
     * fit the pure offline/cloud split) → file lookup (if a filename was mentioned) →
     * offline brain → Gemini cloud fallback.
     */
    
    /** MCU-style context: long-term memory + current utterance for smarter cloud replies. */
    private fun buildJarvisContext(currentSpeech: String): String {
        val mem = PersistentMemory(this).buildMemoryContext(
            SettingsManager(this).getUserName()
        )
        return buildString {
            append(mem)
            append("\n\nCurrent user utterance: ")
            append(currentSpeech)
            append("\nRespond as JARVIS: anticipate needs, be precise, stay in character.")
        }
    }

    
    private suspend fun buildMorningBriefing(): String {
        val sb = StringBuilder()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        val name = try {
            SettingsManager(this).getUserName().let {
                if (it.isBlank() || it == "User") "sir" else it
            }
        } catch (_: Exception) {
            "sir"
        }
        sb.append("$hello, $name. ")

        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        sb.append("The time is $time. ")

        try {
            val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            val bat = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sb.append("Power levels are at $bat percent. ")
        } catch (_: Exception) {
        }

        val online = try {
            networkStatus.isOnline()
        } catch (_: Exception) {
            false
        }
        sb.append(if (online) "Network is online. " else "Network is offline. ")

        // Calendar (next events)
        try {
            val cal = com.jarvis.assistant.util.CalendarHelper(this).formatBrief(2)
            if (!cal.contains("permission is not granted") && !cal.contains("No upcoming")) {
                sb.append("Calendar: ").append(cal).append(" ")
            } else if (cal.contains("No upcoming")) {
                sb.append("No upcoming calendar events. ")
            }
        } catch (_: Exception) {
        }

        // Notifications summary (short)
        try {
            val notifs = com.jarvis.assistant.notifications.JarvisNotificationListener.summaryOrHelp(5)
            if (!notifs.contains("Notification access is off") && !notifs.contains("No active")) {
                // Keep briefing shorter
                val short = if (notifs.length > 350) notifs.take(350) + "…" else notifs
                sb.append(short).append(" ")
            } else if (notifs.contains("Notification access is off")) {
                sb.append("Notification access is off. ")
            }
        } catch (_: Exception) {
        }

        if (online) {
            try {
                val loc = locationHelper.getCurrentLocation()
                if (loc != null) {
                    val w = weatherClient.getWeather(loc.lat, loc.lon)
                    if (w != null) {
                        sb.append("Weather in ${loc.cityName}: ${w.tempCelsius} degrees, ${w.condition}. ")
                    }
                }
            } catch (_: Exception) {
            }
            try {
                val headlines = com.jarvis.assistant.util.NewsClient().topHeadlines(3)
                if (headlines.isNotEmpty()) {
                    sb.append("Top headlines: ")
                    sb.append(headlines.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString(" "))
                    sb.append(" ")
                }
            } catch (_: Exception) {
            }
        } else {
            sb.append("Weather and news unavailable offline. ")
        }

        sb.append("All systems are nominal. How may I assist you?")
        return sb.toString()
    }


    private fun executeAssistantCommand(
        command: com.jarvis.assistant.model.AssistantCommand,
        confirmed: Boolean = false
    ): String {
        if (command.action == "pc_connect") {
            return when (command.target?.trim()?.lowercase()) {
                "on", "start", "enable", "connect" -> pcBridge.start()
                "off", "stop", "disable", "disconnect" -> pcBridge.stop()
                else -> "Say connect PC or disconnect PC."
            }
        }
        // CommandExecutor owns the permission preflight so offline, Groq, Gemini,
        // and deferred actions all follow the same permission + retry path.
        return if (confirmed) executor.executeConfirmed(command) else executor.execute(command)
    }

    private fun commandFromJson(json: org.json.JSONObject): com.jarvis.assistant.model.AssistantCommand {
        val extra = linkedMapOf<String, String>()
        json.optJSONObject("extra")?.let { values ->
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                extra[key] = values.optString(key)
            }
        }
        val steps = json.optJSONArray("steps")?.let { array ->
            (0 until array.length()).map { commandFromJson(array.getJSONObject(it)) }
        }
        return com.jarvis.assistant.model.AssistantCommand(
            action = json.optString("action"),
            target = json.optString("target").takeUnless { it.isBlank() || it == "null" },
            message = json.optString("message").takeUnless { it.isBlank() || it == "null" },
            extra = extra,
            steps = steps
        )
    }

    private fun requiredRuntimePermissions(command: com.jarvis.assistant.model.AssistantCommand): List<String> {
        val permissions = linkedSetOf<String>()
        fun collect(cmd: com.jarvis.assistant.model.AssistantCommand) {
            when (com.jarvis.assistant.model.ActionType.fromKey(cmd.action)) {
                com.jarvis.assistant.model.ActionType.CALL -> {
                    if (cmd.target?.filter { it.isDigit() }.orEmpty().length < 8) {
                        permissions += android.Manifest.permission.READ_CONTACTS
                    }
                    permissions += android.Manifest.permission.CALL_PHONE
                }
                com.jarvis.assistant.model.ActionType.SEND_SMS -> {
                    permissions += android.Manifest.permission.SEND_SMS
                    if (cmd.target?.filter { it.isDigit() }.orEmpty().length < 8) {
                        permissions += android.Manifest.permission.READ_CONTACTS
                    }
                }
                com.jarvis.assistant.model.ActionType.WHATSAPP_CALL,
                com.jarvis.assistant.model.ActionType.WHATSAPP_MESSAGE,
                com.jarvis.assistant.model.ActionType.TELEGRAM_MESSAGE -> {
                    if (cmd.target?.filter { it.isDigit() }.orEmpty().length < 8) {
                        permissions += android.Manifest.permission.READ_CONTACTS
                    }
                }
                com.jarvis.assistant.model.ActionType.MULTI_STEP -> cmd.steps.orEmpty().forEach(::collect)
                else -> Unit
            }
        }
        collect(command)
        return permissions.toList()
    }

    /**
     * Voice lock verifies phrase and speaker from the exact same PCM capture.
     * This prevents mixing a spoken challenge from one person with a replayed
     * enrolled-speaker sample from another. It is not a full anti-spoof/liveness proof.
     */
    private suspend fun authenticateVoiceForCommand(): Boolean {
        if (!voiceAuth.isEnabled()) return true
        if (!voiceAuth.isEnrolled()) return false

        val phrase = com.jarvis.assistant.voice.VoiceChallenge.create()
        withContext(Dispatchers.Main) {
            tts.speak("Voice security check. Say these four words: $phrase")
        }
        val promptFinished = withTimeoutOrNull(20_000L) {
            while (tts.isSpeaking()) delay(100L)
            true
        } ?: false
        if (!promptFinished) {
            android.util.Log.w("JarvisService", "Voice challenge prompt did not finish; refusing to capture during playback")
            tts.stop()
            return false
        }

        // One microphone recording feeds both the offline ASR and CAM++ speaker model.
        val sample = withContext(Dispatchers.IO) {
            voiceAuth.captureSample(6500)
        } ?: return false

        val transcript = withContext(Dispatchers.Default) {
            com.jarvis.assistant.voice.VoiceChallengeRecognizer(applicationContext)
                .recognize(sample)
        } ?: return false

        if (!com.jarvis.assistant.voice.VoiceChallenge.matches(phrase, transcript)) return false
        return voiceAuth.verify(sample)
    }

    private suspend fun processSpeech(speech: String, requireVoiceAuth: Boolean = true): Pair<String, Boolean> {

        // Authenticate before confirmation, cancellation, routing, or any action execution.
        if (requireVoiceAuth && !authenticateVoiceForCommand()) {
            return "Voice verification failed. No command was executed. Please try again." to false
        }

        // Handle explicit confirmation before OfflineBrain or any cloud model can consume it.
        if (RiskConfirmGate.hasPending()) {
            when {
                RiskConfirmGate.isCancelSpeech(speech) -> {
                    RiskConfirmGate.cancel()
                    return "Cancelled. No action was executed." to false
                }
                RiskConfirmGate.isConfirmSpeech(speech) -> {
                    val pending = RiskConfirmGate.takeIfConfirm(speech)
                    if (pending != null) {
                        return try {
                            executeAssistantCommand(pending, confirmed = true) to false
                        } catch (e: Exception) {
                            "The confirmed action failed: ${e.message ?: "unknown error"}" to false
                        }
                    }
                    RiskConfirmGate.cancel()
                    return "The confirmation expired. Please repeat the command." to false
                }
                else -> RiskConfirmGate.cancel() // New request cancels the previous pending action.
            }
        }

        // Complex/multi-step requests continue through the same OfflineBrain -> Groq -> Gemini
        // structured-command pipeline. Do not split into "Done" placeholders here.

        weatherReplyIfAsked(speech)?.let {
            offlineBrain.recordInteraction()
            return it to false
        }

        // Checked before the offline brain so a filename like "upi_osint.py" isn't
        // misread as a "check <contact>"-style command.
        fileCommandReplyIfAsked(speech)?.let {
            offlineBrain.recordInteraction()
            return it to true
        }

        val offlineReply = try {
            offlineBrain.handle(speech)
        } catch (e: SecurityException) {
            "I don't have permission to do that. Please grant it in the app's settings."
        } catch (e: Exception) {
            "Something went wrong running that command."
        }

        val result = if (offlineReply == "REQUEST_BRIEFING") {
            try {
                val b = com.jarvis.assistant.util.BriefingHelper(this).build(includeWeather = networkStatus.isOnline())
                b.text to true
            } catch (_: Exception) {
                buildMorningBriefing() to true
            }
        } else if (offlineReply != null) {
            offlineReply to false
        } else if (networkStatus.isOnline()) {
            val groqClient = JarvisLlmClient(
                apiKeyProvider = {
                    SettingsManager(this).getGroqApiKey()
                }
            )
            val groqCommand = try {
                groqClient.getCommand(speech, buildJarvisContext(speech))
            } catch (e: Exception) {
                android.util.Log.e("JarvisService", "Groq command planning failed", e)
                null
            }
            if (groqCommand != null) {
                try { com.jarvis.assistant.ui.HudController.executing() } catch (_: Exception) {}
                try {
                    executeAssistantCommand(groqCommand) to true
                } catch (e: Exception) {
                    android.util.Log.e("JarvisService", "Groq action execution failed", e)
                    "The requested action could not be executed: ${e.message ?: "unknown error"}" to true
                }
            } else {
                try {
                    // Gemini is the structured-command fallback, using the same executor.
                    val command = gemini.getCommand(speech)
                    try { com.jarvis.assistant.ui.HudController.executing() } catch (_: Exception) {}
                    executeAssistantCommand(command) to true
                } catch (e: Exception) {
                    android.util.Log.e("JarvisService", "Both cloud command planners failed", e)
                    "I couldn't interpret or execute that request. Please try a clearer command." to true
                }
            }
        } else {
            "I'm currently offline and lack a local protocol for that request, sir." to false
        }

        offlineBrain.recordInteraction()
        return result
    }

    /**
     * If the user mentioned a filename (e.g. "upi_osint.py padho" or "fix errors in main.py"),
     * finds it on shared storage, reads it, and hands it to the cloud LLM along with the
     * user's original phrasing. Returns null if no filename was mentioned so the normal
     * pipeline continues untouched.
     */
    private suspend fun fileCommandReplyIfAsked(speech: String): String? {
        val fileName = FileFinder.extractFileName(speech) ?: return null

        if (!FileFinder.hasStorageAccess()) {
            return "I need storage access to read files, sir. Please grant \"All files access\" " +
                "for Jarvis in Settings, then try again."
        }
        if (!networkStatus.isOnline()) {
            return "I found that you mean a file, sir, but reading it needs the cloud brain and I'm offline right now."
        }

        val file = withContext(Dispatchers.IO) { FileFinder.findFile(fileName) }
            ?: return "I couldn't find a file named \"$fileName\" on this device, sir."

        val content = withContext(Dispatchers.IO) { FileFinder.readTextCapped(file) }
            ?: return "I found \"$fileName\" but couldn't read it, sir — it may not be a text file."

        val combined = buildString {
            append("The user said: \"").append(speech).append("\"\n\n")
            append("Attached file \"").append(fileName).append("\" (path: ").append(file.absolutePath).append("):\n\n")
            append("```\n").append(content).append("\n```\n\n")
            append("Respond to their request about this file.")
        }

        val result = try {
            JarvisLlmClient(apiKeyProvider = { SettingsManager(this).getGroqApiKey() }).chat(combined)
        } catch (e: Exception) {
            null
        }

        return if (result != null && result.ok && result.text.isNotBlank()) {
            result.text
        } else {
            "I found the file but couldn't process it right now, sir."
        }
    }

    /** Phase 5 — "Live weather aur location". Returns null if the speech isn't a weather question. */
    private suspend fun weatherReplyIfAsked(speech: String): String? {
        val lower = speech.lowercase()
        val isWeatherQuestion = listOf("weather", "temperature", "mausam", "kitni garmi", "kitni thand")
            .any { lower.contains(it) }
        if (!isWeatherQuestion) return null

        if (!networkStatus.isOnline()) return "I need an internet connection to check the weather."

        val location = locationHelper.getCurrentLocation()
            ?: return "I couldn't get your current location. Make sure location permission is granted."
        val weather = weatherClient.getWeather(location.lat, location.lon)
            ?: return "I couldn't reach the weather service just now."

        return "It's ${weather.tempCelsius}°C and ${weather.condition} in ${location.cityName}."
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Assistant", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Phase 5 — "Daily activity summary", scheduled once for ~8 PM every day. */
    private fun scheduleDailySummary() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val receiverIntent = Intent(this, DailySummaryReceiver::class.java).apply {
            action = DailySummaryReceiver.ACTION_DAILY_SUMMARY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }

        try {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                triggerTime.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM not granted on some OEMs — daily summary just won't fire;
            // "my routine" by voice still works on demand regardless.
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val listenIntent = Intent(this, com.jarvis.assistant.widget.JarvisWidgetActionReceiver::class.java).apply {
            action = com.jarvis.assistant.widget.JarvisWidgetActionReceiver.ACTION_START_LISTENING
        }
        val listenPendingIntent = PendingIntent.getBroadcast(
            this, 0, listenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = Intent(this, com.jarvis.assistant.MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 2, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S")
            .setContentText(text.ifBlank { "Standing by · tap Listen" })
            .setSmallIcon(R.drawable.ic_mic)
            .setColor(0xFF00D9FF.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text.ifBlank { "Standing by. Use Listen for voice commands without unlocking." })
            )
            .addAction(R.drawable.ic_mic, "Listen", listenPendingIntent)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AssistantForegroundService = this@AssistantForegroundService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Prevent late IO/start callbacks or command coroutines from reviving work
        // after Android has destroyed this foreground service.
        scope.cancel()
        try { callAnnouncer?.stop() } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        proactive?.stop()
        proactive = null
        wakeStartGeneration++
        wakeStartInProgress = false
        try { voicuteWake?.release() } catch (_: Exception) {}
        releaseWakeCpuLock()
        stt.stopContinuous()
        stt.destroy()
        tts.shutdown()
        if (pcBridge.isRunning) pcBridge.stop()
        super.onDestroy()
    }
}
