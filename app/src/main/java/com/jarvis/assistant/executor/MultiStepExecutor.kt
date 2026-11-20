package com.jarvis.assistant.executor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.model.ActionType
import com.jarvis.assistant.model.AssistantCommand

/**
 * Stark Layer 2 — run a chain until done or hard fail.
 * Sequential, paced, reports which step failed.
 */
class MultiStepExecutor(
    private val context: Context,
    private val runOne: (AssistantCommand) -> String
) {
    data class Report(
        val ok: Boolean,
        val summary: String,
        val stepResults: List<String>
    )

    fun execute(
        steps: List<AssistantCommand>,
        paceMs: Long = 0L
    ): Report {
        if (steps.isEmpty()) {
            return Report(false, "No steps to execute.", emptyList())
        }
        if (steps.size > MAX_STEPS) {
            return Report(false, "Sequence has ${steps.size} steps; the maximum is $MAX_STEPS.", emptyList())
        }
        val results = mutableListOf<String>()
        val total = steps.size
        for ((index, step) in steps.withIndex()) {
            val n = index + 1
            val label = step.action.ifBlank { "step" }
            try {
                // Small pace so UI / accessibility can settle
                if (index > 0 && paceMs > 0) {
                    try {
                        Thread.sleep(paceMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return Report(
                            false,
                            "Stopped at step $n of $total.",
                            results
                        )
                    }
                }
                val out = runOne(step).trim()
                results += out
                if (isHardFail(out)) {
                    return Report(
                        ok = false,
                        summary = "Failed at step $n of $total ($label): $out",
                        stepResults = results
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "step $n failed", e)
                val msg = e.message ?: "error"
                results += msg
                return Report(
                    ok = false,
                    summary = "Failed at step $n of $total ($label): $msg",
                    stepResults = results
                )
            }
        }
        val short = results.lastOrNull()?.take(120)?.ifBlank { null }
        val summary = if (short != null) {
            "Done. $total steps complete. $short"
        } else {
            "Done. All $total steps complete."
        }
        return Report(true, summary, results)
    }

    /**
     * Build a chain from plain speech when LLM steps are missing.
     * Offline patterns only — no API.
     */
    fun parseOfflineChain(speech: String): List<AssistantCommand>? {
        val s = speech.trim()
        val lower = s.lowercase()

        // WhatsApp message chains require both a recipient and message body.
        // Never report "message ready" for a REPLY action: that does not send anything.
        Regex(
            """open\s+whatsapp\s+and\s+(?:send|message|text)\s+(.+?)\s+to\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            val message = m.groupValues[1].trim()
            val recipient = m.groupValues[2].trim()
            if (message.isNotBlank() && recipient.isNotBlank()) {
                return listOf(
                    AssistantCommand(action = ActionType.OPEN_APP.key, target = "WhatsApp"),
                    AssistantCommand(
                        action = ActionType.WHATSAPP_MESSAGE.key,
                        target = recipient,
                        message = message
                    )
                )
            }
        }

        // open X then / and open Y
        Regex(
            """open\s+(.+?)\s+(?:and then|and|phir|then)\s+open\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            return listOf(
                AssistantCommand(action = ActionType.OPEN_APP.key, target = m.groupValues[1].trim()),
                AssistantCommand(action = ActionType.OPEN_APP.key, target = m.groupValues[2].trim())
            )
        }

        // Explicit torch state followed by opening another app.
        Regex(
            """(?:turn\s+)?(?:the\s+)?(?:torch|flashlight)\s+(on|off)\s+(?:and then|and|phir|then)\s+open\s+(.+)""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { match ->
            val state = if (match.groupValues[1].equals("on", true)) "torch_on" else "torch_off"
            return listOf(
                AssistantCommand(action = ActionType.TOGGLE_SETTING.key, target = state),
                AssistantCommand(action = ActionType.OPEN_APP.key, target = match.groupValues[2].trim())
            )
        }

        // Open an app first, then set the torch explicitly.
        Regex(
            """open\s+(.+?)\s+(?:and then|and|phir|then)\s+(?:turn\s+)?(?:the\s+)?(?:torch|flashlight)\s+(on|off)""",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { match ->
            val state = if (match.groupValues[2].equals("on", true)) "torch_on" else "torch_off"
            return listOf(
                AssistantCommand(action = ActionType.OPEN_APP.key, target = match.groupValues[1].trim()),
                AssistantCommand(action = ActionType.TOGGLE_SETTING.key, target = state)
            )
        }

        // flashlight then brightness-style: torch on and ...
        if (lower.contains("torch") || lower.contains("flashlight")) {
            val steps = mutableListOf<AssistantCommand>()
            if (lower.contains("on") || lower.contains("chalu")) {
                steps += AssistantCommand(action = ActionType.TOGGLE_SETTING.key, target = "torch_on")
            } else if (lower.contains("off") || lower.contains("band")) {
                steps += AssistantCommand(action = ActionType.TOGGLE_SETTING.key, target = "torch_off")
            }
            if (steps.isNotEmpty() && (lower.contains("and") || lower.contains("phir"))) {
                return steps
            }
        }

        return null
    }

    private fun isHardFail(out: String): Boolean {
        val l = out.lowercase()
        val keys = listOf(
            "couldn't", "could not", "failed", "unable", "not found",
            "permission", "i didn't understand", "unknown", "unknown setting", "error",
            "which application", "which app"
        )
        return keys.any { l.contains(it) }
    }

    companion object {
        private const val TAG = "MultiStepExecutor"
        const val MAX_STEPS = 5
    }
}
