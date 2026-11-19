package com.jarvis.assistant.executor

import com.jarvis.assistant.model.ActionType
import com.jarvis.assistant.model.AssistantCommand

/**
 * Stark L6 — high-risk actions require a spoken confirm.
 * Pending lives in-memory for a short window (default 45s).
 */
object RiskConfirmGate {
    data class Pending(
        val command: AssistantCommand,
        val prompt: String,
        val expiresAt: Long
    )

    @Volatile
    private var pending: Pending? = null

    private const val WINDOW_MS = 45_000L

    fun isConfirmSpeech(speech: String): Boolean {
        val l = speech.lowercase().trim()
        return l in setOf(
            "yes", "yeah", "yep", "confirm", "do it", "proceed", "go ahead",
            "ha", "haan", "han", "kar do", "karo", "ok", "okay"
        ) || l.startsWith("yes ") || l.startsWith("confirm")
    }

    fun isCancelSpeech(speech: String): Boolean {
        val l = speech.lowercase().trim()
        return l in setOf(
            "no", "cancel", "stop", "don't", "do not", "abort",
            "mat", "nahi", "na", "cancel karo"
        ) || l.startsWith("no ")
    }

    fun isRisky(cmd: AssistantCommand): Boolean {
        val type = try {
            ActionType.fromKey(cmd.action)
        } catch (_: Exception) {
            return false
        }
        return when (type) {
            // Calls remain immediate when explicitly requested. Messages and destructive
            // actions require a second confirmation to prevent speech-recognition mistakes.
            ActionType.CALL -> false
            ActionType.MULTI_STEP,
            ActionType.UNLOCK_APP,
            ActionType.UNINSTALL_APP,
            ActionType.SEND_SMS,
            ActionType.WHATSAPP_MESSAGE,
            ActionType.TELEGRAM_MESSAGE,
            ActionType.SET_PIN,
            ActionType.UNLOCK_PHONE -> true
            else -> {
                val a = cmd.action.lowercase()
                a.contains("delete") || a.contains("wipe")
            }
        }
    }

    fun promptFor(cmd: AssistantCommand): String {
        val type = try {
            ActionType.fromKey(cmd.action)
        } catch (_: Exception) {
            cmd.action
        }
        return when (type) {
            ActionType.CALL ->
                "Call ${cmd.target ?: "this number"}? Say confirm or cancel."
            ActionType.MULTI_STEP ->
                "Run a multi-step sequence? Say confirm or cancel."
            ActionType.UNLOCK_APP ->
                "Unlock ${cmd.target ?: "the app"}? Say confirm or cancel."
            ActionType.SEND_SMS, ActionType.WHATSAPP_MESSAGE, ActionType.TELEGRAM_MESSAGE ->
                "Send the message to ${cmd.target ?: "this recipient"}? Say confirm or cancel."
            ActionType.UNINSTALL_APP ->
                "Uninstall ${cmd.target ?: "this app"}? Say confirm or cancel."
            ActionType.SET_PIN ->
                "Set or change the device app PIN? Say confirm or cancel."
            else ->
                "Proceed with ${cmd.action}? Say confirm or cancel."
        }
    }

    fun hold(cmd: AssistantCommand): String {
        val p = promptFor(cmd)
        pending = Pending(cmd, p, System.currentTimeMillis() + WINDOW_MS)
        return p
    }

    /** @return pending command if confirm and still valid */
    fun takeIfConfirm(speech: String): AssistantCommand? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return null
        }
        if (isCancelSpeech(speech)) {
            pending = null
            return null
        }
        if (!isConfirmSpeech(speech)) return null
        pending = null
        return p.command
    }

    fun cancel(): String {
        pending = null
        return "Cancelled."
    }

    fun hasPending(): Boolean = pending != null &&
        System.currentTimeMillis() <= (pending?.expiresAt ?: 0L)
}
