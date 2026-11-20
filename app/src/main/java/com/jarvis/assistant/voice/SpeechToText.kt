package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechToText(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listenOnce(onResult: (String) -> Unit, onError: () -> Unit, preferOffline: Boolean = false) {
        stopInternal()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(simpleListener(onResult, onError))
        recognizer?.startListening(buildIntent(preferOffline))
    }

    /** Continuous Google STT wake fallback is removed; Voicute is the only wake engine. */
    fun stopContinuous() {
        stopInternal()
    }

    private fun stopInternal() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun simpleListener(onResult: (String) -> Unit, onError: () -> Unit) =
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val transcript = bestTranscript(results)
                stopInternal()
                onResult(transcript)
            }
            override fun onError(error: Int) {
                stopInternal()
                onError()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    private fun bestTranscript(results: Bundle?): String {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
        if (matches.isEmpty()) return ""
        val boost = listOf(
            "jarvis", "screen", "analyze", "analyse", "weather", "mausam",
            "remember", "macro", "whatsapp", "call", "brief", "morning",
            "chrome", "bluetooth", "flashlight", "volume", "alarm"
        )
        return matches.filter { it.isNotBlank() }.maxByOrNull { m ->
            val lower = m.lowercase()
            var score = m.length
            boost.forEach { if (lower.contains(it)) score += 40 }
            score
        } ?: matches.first()
    }

    private fun buildIntent(preferOffline: Boolean = false) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Command capture only. Voicute owns continuous wake detection.
        // Keep natural pauses, but don't force users to wait several seconds
        // after finishing a short command before the final transcript arrives.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
    }

    fun destroy() {
        stopInternal()
    }
}
