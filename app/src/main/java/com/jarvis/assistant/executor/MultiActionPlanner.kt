package com.jarvis.assistant.executor

import com.jarvis.assistant.ai.OllamaClient
import com.jarvis.assistant.model.ActionType
import com.jarvis.assistant.model.AssistantCommand
import org.json.JSONObject

/** Validates a complete local-AI action plan before any device action is executed. */
class MultiActionPlanner(private val ollama: OllamaClient) {
    data class PlanResult(val ok: Boolean, val steps: List<AssistantCommand> = emptyList(), val error: String? = null)

    suspend fun plan(speech: String): PlanResult {
        if (!looksLikeMultiActionRequest(speech)) return PlanResult(false, error = "Not a multi-action request.")
        val prompt = """
Convert the user's request into an ordered phone-action plan.
Return ONLY JSON: {"steps":[{"action":"open_app","target":"Chrome","message":null}]}
Rules:
- Return 2 to 5 steps in exact requested order.
- Allowed actions only: open_app, toggle_setting, set_volume, media_control, set_timer,
  set_alarm, web_search, play_media, open_clock, focus_mode, open_url, read_screen,
  screenshot, analyze_screen, open_vision, briefing, recall, debrief.
- If any requested action is unsupported, ambiguous, or missing required details, return {"steps":[]}.
- Never invent or silently omit an action. No nested steps, extra fields, Markdown or commentary.
- open_app target is exact app name; web_search target is query; open_url target is complete URL.
- play_media target is media query and message may be app hint.
- set_timer/set_alarm target contains the duration/time explicitly requested.
User request: ${speech.take(MAX_REQUEST_CHARS)}
""".trimIndent()
        val response = ollama.chat(prompt)
        if (!response.ok) return PlanResult(false, error = response.error ?: "Local planner unavailable.")
        return parsePlan(response.text)
    }

    fun parsePlan(raw: String): PlanResult {
        val jsonText = extractJson(raw) ?: return PlanResult(false, error = "Planner did not return JSON.")
        val root = try { JSONObject(jsonText) } catch (_: Exception) {
            return PlanResult(false, error = "Planner returned malformed JSON.")
        }
        val array = root.optJSONArray("steps") ?: return PlanResult(false, error = "Missing steps array.")
        if (array.length() !in MIN_STEPS..MAX_STEPS) return PlanResult(false, error = "Planner must return 2 to 5 actions.")

        val allowed = setOf(
            ActionType.OPEN_APP.key, ActionType.TOGGLE_SETTING.key, ActionType.SET_VOLUME.key,
            ActionType.MEDIA_CONTROL.key, ActionType.SET_TIMER.key, ActionType.SET_ALARM.key,
            ActionType.WEB_SEARCH.key, ActionType.PLAY_MEDIA.key, ActionType.OPEN_CLOCK.key,
            ActionType.FOCUS_MODE.key, ActionType.OPEN_URL.key, ActionType.READ_SCREEN.key,
            ActionType.SCREENSHOT.key, ActionType.ANALYZE_SCREEN.key, ActionType.OPEN_VISION.key,
            ActionType.BRIEFING.key, ActionType.RECALL.key, ActionType.DEBRIEF.key
        )
        val requiresTarget = setOf(
            ActionType.OPEN_APP.key, ActionType.TOGGLE_SETTING.key, ActionType.SET_VOLUME.key,
            ActionType.MEDIA_CONTROL.key, ActionType.SET_TIMER.key, ActionType.SET_ALARM.key,
            ActionType.WEB_SEARCH.key, ActionType.PLAY_MEDIA.key, ActionType.OPEN_CLOCK.key,
            ActionType.FOCUS_MODE.key, ActionType.OPEN_URL.key, ActionType.OPEN_VISION.key
        )
        val steps = mutableListOf<AssistantCommand>()
        for (index in 0 until array.length()) {
            val item = try { array.getJSONObject(index) } catch (_: Exception) {
                return PlanResult(false, error = "Action ${index + 1} is not an object.")
            }
            val action = item.optString("action").trim().lowercase()
            val target = item.optString("target").takeUnless { it.isBlank() || it == "null" }
            val message = item.optString("message").takeUnless { it.isBlank() || it == "null" }
            if (action !in allowed) return PlanResult(false, error = "Action ${index + 1} is not allowed: $action")
            if (action in requiresTarget && target.isNullOrBlank()) return PlanResult(false, error = "Action ${index + 1} is missing its target.")
            if (item.has("steps") || item.has("extra")) return PlanResult(false, error = "Nested or extra action data is not accepted.")
            steps += AssistantCommand(action = action, target = target, message = message)
        }
        return PlanResult(true, steps = steps)
    }

    fun looksLikeMultiActionRequest(speech: String): Boolean {
        val lower = speech.lowercase()
        val connector = listOf(" and then ", " then ", " after that ", " and ", " phir ", " uske baad ", " aur phir ", " aur ")
            .any { lower.contains(it) }
        if (!connector) return false
        val verbs = listOf("open", "launch", "start", "turn", "switch", "set", "increase", "decrease",
            "play", "search", "show", "read", "screenshot", "check", "kholo", "chalu", "band",
            "on karo", "off karo", "bajao", "dikhao", "lagao")
        return verbs.count { lower.contains(it) } >= 2
    }

    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        val fenced = Regex("(?s)\u0060\u0060\u0060(?:json)?\\s*(\\{.*?\\})\\s*\u0060\u0060\u0060", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.get(1)
        if (fenced != null) return fenced
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else null
    }

    companion object {
        const val MAX_STEPS = 5
        private const val MIN_STEPS = 2
        private const val MAX_REQUEST_CHARS = 1200
    }
}
