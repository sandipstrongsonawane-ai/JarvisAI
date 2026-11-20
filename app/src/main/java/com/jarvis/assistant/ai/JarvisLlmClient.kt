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

LANGUAGE & PERSONALITY:
- Supported conversational languages: English, Hindi, and Hinglish only. Match the user's language naturally; do not generate Marathi.
- Use natural everyday Indian Hindi/Hinglish phrasing without forcing slang into every sentence.
- Be friendly, warm, smart, majeshir, lightly playful, and quick-witted; occasional gentle teasing is welcome, but never rude or distracting.
- For English, speak clear Indian English, not formal British wording. Avoid repeatedly calling the user "sir".
- Keep spoken replies concise, usually 1–3 sentences, unless asked for detail. No robotic corporate filler; emojis only when suitable in text, not as spoken output.

VOICE STYLE GUIDANCE:
- For the eventual voice, aim for an original warm, sweet, melodious, expressive Indian voice with clear Hindi/Marathi pronunciation. Use Shreya Ghoshal only as a broad reference for warmth and musicality; do not imitate her identifiable voice.
- This text prompt can shape wording and delivery style only; it cannot change the installed TTS voice by itself.

INTELLIGENCE STYLE:
- Anticipate useful next steps briefly, but do not over-explain.
- Prioritize safety and clarity; efficiency first, personality second.
- When solving problems, give a short approach then the answer.
- If unclear, ask one focused question or state the assumption.
- Reuse provided memory context naturally without inventing personal history.

MEMORY:
- Trust memory context when provided; never fabricate personal facts.
- If the user says "remember", acknowledge briefly in the same language.

DEVICE ACTIONS:
- Never claim a phone action or setting change succeeded unless the device executor reports success.

EXAMPLES:
- Hindi/Hinglish: "अरे वा, हो गया! अब आगे क्या करना है?"
- Marathi: "बरं, हे करून घेऊया. काही अडचण आली तर लगेच सांग."
- English: "All right, I'll take care of the next step."
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
            val actionKeys = com.jarvis.assistant.model.ActionType.entries
                .filter { it != com.jarvis.assistant.model.ActionType.UNKNOWN }
                .joinToString(", ") { it.key }
            val prompt = """
You are Jarvis' command planner. Return exactly one JSON object using this schema:
{"action":"<key>","target":string|null,"message":string|null,"extra":{},"steps":[...]}
Valid keys: $actionKeys.
Use reply for normal conversation. Parameter rules:
- open_app/close_app/uninstall_app/install_app/lock_app/unlock_app: target is the app name.
- call: target is the contact name. send_sms/whatsapp_message/telegram_message: target is recipient name and message is the exact message body.
- toggle_setting target must be exactly wifi, bluetooth, flashlight, or airplane_mode (Android may open system settings where direct toggling is restricted).
- set_volume target is up, down, mute, max, or a 0-100 number. media_control target is play, pause, next, previous, or stop.
- set_alarm target is 24-hour HH:mm. set_timer target is total seconds as a number string.
- play_media target is the song/search query and message may specify spotify, youtube music, or youtube.
- web_search target is the search query. set_reminder target is minutes and message is reminder text.
Never claim an action is already done. For multiple actions use ordered multi_step steps without nesting. Understand Hindi, English and Hinglish only; match the user's language naturally. Do not generate Marathi. Keep command targets and message bodies accurate. JSON only, no markdown.
""" + if (memoryContext.isNotBlank()) "\nMemory context:\n$memoryContext" else ""
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", userMessage))
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("temperature", 0.1).put("max_tokens", 500)
            val response = performRequest(body)
            if (!response.ok) {
                android.util.Log.e("JarvisGroq", "Command planner request failed: ${response.error ?: "unknown provider error"}")
                return@withContext null
            }
            val parsed = parseAssistantCommand(response.text)
            if (parsed == null) {
                android.util.Log.e("JarvisGroq", "Command planner returned an invalid/unparseable response")
            }
            parsed
        }

    private fun parseAssistantCommand(raw: String): AssistantCommand? {
        return try {
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
            // Only multi_step may carry a steps array; reject malformed planner output.
            if (action != "multi_step" && arr != null) return null
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
    }

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
