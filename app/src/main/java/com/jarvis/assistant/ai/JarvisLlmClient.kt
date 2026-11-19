package com.jarvis.assistant.ai

import com.jarvis.assistant.model.AssistantCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud LLM for Jarvis — Groq (OpenAI-compatible), free + fast.
 * Put key in local.properties: GROQ_API_KEY=gsk_...
 * and expose via BuildConfig, or pass at runtime from secure storage.
 *
 * Model notes (checked against Groq's deprecations page):
 *  - llama-3.1-8b-instant was deprecated June 17, 2026 → replaced with openai/gpt-oss-20b.
 *  - Vision (image) requests need a multimodal model; qwen/qwen3.6-27b is Groq's current
 *    documented production vision model as of this writing. Groq's lineup changes often —
 *    if image replies start failing, check console.groq.com/docs/vision for the current ID.
 */
class JarvisLlmClient(
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = "https://api.groq.com/openai/v1",
    private val model: String = "openai/gpt-oss-20b",
    private val visionModel: String = "qwen/qwen3.6-27b"
) {
    data class Result(
        val ok: Boolean,
        val text: String,
        val error: String? = null
    )

    companion object {
        val SYSTEM_PROMPT = """
You are J.A.R.V.I.S. — Just A Rather Very Intelligent System — the same AI that serves Tony Stark in the Iron Man films and the MCU.

WHO YOU ARE:
- Not a chatbot. Not "an AI assistant product". You are JARVIS: calm, brilliant, loyal, slightly dry, always composed under pressure.
- You think ahead. You connect dots from MEMORY CONTEXT and recent conversation. You notice implications the user may not have stated.
- You are precise with facts. You never bluff. If you lack data, you say so cleanly and offer the next best step.

HOW YOU SPEAK:
- Formal British English when the user writes in English. Polite register in other languages; match the user's language.
- Address the user as "sir" in English (unless they ask otherwise).
- Concise for speech: usually 1–3 sentences. Expand only when asked for detail, analysis, or plans.
- No emojis. No slang. No corporate filler ("Happy to help!", "Absolutely!").
- Signature restraint: understated wit, never comedy routines.

INTELLIGENCE STYLE (MCU):
- Anticipate: if they ask about weather before a trip, mention practical implications briefly.
- Prioritize: safety and clarity first; efficiency second; flair last.
- When solving problems, outline the approach briefly, then the answer.
- When the user is vague, ask one sharp clarifying question — or state the assumption you are using.
- Remember and reuse personal facts from MEMORY CONTEXT naturally ("Given that you are in Delhi, sir…") without repeating the whole memory dump.

MEMORY:
- TRUST MEMORY CONTEXT when provided. Do not invent personal history.
- If they say "remember…", acknowledge: "I've made a note of that, sir."

DEVICE ACTIONS:
- You do NOT claim to have placed calls, sent SMS, opened apps, or changed settings. On-device systems do that. You may discuss results only if the user reports them.

EXAMPLES OF TONE:
- "Of course, sir."
- "Working on it, sir."
- "All systems are nominal."
- "I'd advise against that, sir — unless you are prepared for the trade-off."
- "I've taken the liberty of factoring that in."
- "I'm afraid I don't have that information, sir. Shall I work from an assumption?"
""".trimIndent()
    }

    suspend fun chat(userMessage: String, history: List<Pair<String, String>> = emptyList(), memoryContext: String = ""): Result =
        withContext(Dispatchers.IO) {
            if (userMessage.isBlank()) {
                return@withContext Result(false, "", "Empty message")
            }
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.takeLast(8).forEach { (role, content) ->
                val r = if (role == "assistant") "assistant" else "user"
                messages.put(JSONObject().put("role", r).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", userMessage))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.45)
                .put("max_tokens", 700)

            performRequest(body)
        }

    /** Convert user speech into a validated structured command for the shared executor. */
    suspend fun getCommand(userMessage: String, memoryContext: String = ""): AssistantCommand? =
        withContext(Dispatchers.IO) {
            if (userMessage.isBlank()) return@withContext null
            val prompt = """
You are Jarvis' command planner. Return exactly one JSON object using this schema:
{"action":"<key>","target":string|null,"message":string|null,"extra":{},"steps":[...]}
Valid keys: open_app, close_app, clear_recents, uninstall_app, install_app, call, send_sms, toggle_setting, read_screen, screenshot, analyze_screen, set_volume, media_control, set_alarm, set_timer, open_clock, stopwatch, open_vision, web_search, play_media, lock_app, unlock_app, unlock_phone, set_pin, pc_connect, app_call, whatsapp_call, whatsapp_message, telegram_message, set_reminder, read_clipboard, open_clipboard_link, notif_summary, search_sms, search_photos, search_mail, agent_task, create_event, rewrite_text, now_playing, share_screen, screen_save_phone, screen_email, screen_open_link, proactive_tip, calendar_next, focus_mode, remember, recall, debrief, briefing, screen_act, open_url, multi_step, reply.
Use reply for normal conversation. For actions, specify the correct key and parameters; never claim the action is already done. For multiple actions use ordered multi_step steps without nesting. Understand Hindi, English and Hinglish; match the user's language. JSON only, no markdown.
""" + if (memoryContext.isNotBlank()) "\nMemory context:\n$memoryContext" else ""
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", userMessage))
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("temperature", 0.1).put("max_tokens", 500)
            val response = performRequest(body)
            if (!response.ok) return@withContext null
            parseAssistantCommand(response.text)
        }

    private fun parseAssistantCommand(raw: String): AssistantCommand? = try {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = JSONObject(cleaned.substring(start, end + 1))
        fun parseOne(obj: JSONObject): AssistantCommand? {
            val action = obj.optString("action", "reply").trim().lowercase()
            if (com.jarvis.assistant.model.ActionType.fromKey(action) == com.jarvis.assistant.model.ActionType.UNKNOWN) return null
            val target = obj.optString("target").takeUnless { it.isBlank() || it == "null" }
            val message = obj.optString("message").takeUnless { it.isBlank() || it == "null" }
            val extra = mutableMapOf<String, String>()
            obj.optJSONObject("extra")?.let { values -> values.keys().forEach { key -> extra[key] = values.optString(key) } }
            val arr = obj.optJSONArray("steps")
            val steps = if (arr != null && arr.length() > 0) {
                val parsed = mutableListOf<AssistantCommand>()
                for (i in 0 until arr.length()) {
                    val step = parseOne(arr.getJSONObject(i)) ?: return null
                    if (step.action == "multi_step") return null
                    parsed += step
                }
                parsed
            } else null
            if (action == "multi_step" && steps.isNullOrEmpty()) return null
            return AssistantCommand(action, target, message, extra, steps)
        }
        parseOne(root)
    } catch (_: Exception) { null }
    /**
     * Sends one image plus an optional instruction to a vision-capable Groq model.
     * [base64Image] must be raw base64 (no data: prefix) and [mimeType] like "image/jpeg".
     */
    suspend fun chatWithImage(text: String, base64Image: String, mimeType: String): Result =
        withContext(Dispatchers.IO) {
            if (base64Image.isBlank()) {
                return@withContext Result(false, "", "No image data")
            }
            val content = JSONArray()
            content.put(
                JSONObject().put("type", "text").put(
                    "text",
                    text.ifBlank { "Describe this image." }
                )
            )
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:$mimeType;base64,$base64Image")
                )
            )

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            messages.put(JSONObject().put("role", "user").put("content", content))

            val body = JSONObject()
                .put("model", visionModel)
                .put("messages", messages)
                .put("temperature", 0.4)
                .put("max_tokens", 700)

            performRequest(body)
        }

    private fun performRequest(body: JSONObject): Result {
        val key = apiKeyProvider()?.trim().orEmpty()
        if (key.isEmpty()) {
            return Result(ok = false, text = "", error = "API key missing. Add GROQ_API_KEY.")
        }
        return try {
            val url = URL("$baseUrl/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 45_000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                val errMsg = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw
                } catch (_: Exception) {
                    raw.ifBlank { "HTTP $code" }
                }
                return Result(false, "", errMsg)
            }

            val json = JSONObject(raw)
            val text = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            if (text.isEmpty()) Result(false, "", "Empty model response") else Result(true, text)
        } catch (e: Exception) {
            Result(false, "", e.message ?: "Network error")
        }
    }
}
