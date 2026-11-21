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
        )
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
            // Phone calls are irreversible external actions; require a spoken confirmation.
            ActionType.CALL,
            ActionType.UNLOCK_APP,
            ActionType.UNINSTALL_APP,
            ActionType.SEND_SMS,
            ActionType.WHATSAPP_MESSAGE,
            ActionType.TELEGRAM_MESSAGE,
            ActionType.SET_PIN,
            ActionType.UNLOCK_PHONE -> true
            ActionType.MULTI_STEP -> cmd.steps.orEmpty().any { isRisky(it) }
            ActionType.AGENT_TASK -> isRiskyAgentTask(cmd.target ?: cmd.message.orEmpty())
            else -> {
                val a = cmd.action.lowercase()
                a.contains("delete") || a.contains("wipe")
            }
        }
    }

    private fun isRiskyAgentTask(task: String): Boolean {
        val text = task.lowercase()
        val riskyVerbs = listOf(
            "call", "dial", "send", "message", "text", "whatsapp", "sms",
            "telegram", "uninstall", "delete", "remove", "set pin", "unlock"
        )
        return riskyVerbs.any { verb ->
            Regex("""\b${Regex.escape(verb)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
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
            ActionType.AGENT_TASK ->
                "This task may send a message, place a call, or change something important: ${(cmd.target ?: cmd.message).orEmpty().take(180)}. Say confirm or cancel."
            ActionType.MULTI_STEP -> {
                val details = cmd.steps.orEmpty().take(5).mapIndexed { index, step ->
                    val stepType = ActionType.fromKey(step.action)
                    val description = when (stepType) {
                        ActionType.WHATSAPP_MESSAGE ->
                            "WhatsApp message to ${step.target ?: "unknown recipient"}: ${step.message.orEmpty().take(80)}"
                        ActionType.TELEGRAM_MESSAGE ->
                            "Telegram message to ${step.target ?: "unknown recipient"}: ${step.message.orEmpty().take(80)}"
                        ActionType.SEND_SMS ->
                            "SMS to ${step.target ?: "unknown recipient"}: ${step.message.orEmpty().take(80)}"
                        ActionType.CALL ->
                            "call ${step.target ?: "unknown contact"}"
                        ActionType.OPEN_APP ->
                            "open ${step.target ?: "app"}"
                        ActionType.UNINSTALL_APP ->
                            "uninstall ${step.target ?: "app"}"
                        else -> listOf(step.action, step.target, step.message?.take(60))
                            .filterNot { it.isNullOrBlank() }.joinToString(" ")
                    }
                    "${index + 1}. $description"
                }.joinToString("; ")
                if (details.isBlank()) "Run this multi-step sequence? Say confirm or cancel."
                else "Run these steps: $details. Say confirm or cancel."
            }
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
