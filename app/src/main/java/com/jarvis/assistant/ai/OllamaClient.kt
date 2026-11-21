package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Local Ollama chat client. Requests are sent only to the on-device Termux service;
 * there is deliberately no remote endpoint or API-key fallback.
 */
class OllamaClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    data class Result(val ok: Boolean, val text: String = "", val error: String? = null)

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        memoryContext: String = "",
        emotionalGuidance: String = ""
    ): Result = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) return@withContext Result(false, error = "Empty message")

        val system = buildString {
            append(SYSTEM_PROMPT)
            if (memoryContext.isNotBlank()) {
                append("\n\nPERSONAL MEMORY (user-controlled, on this device):\n")
                append(memoryContext.take(MAX_MEMORY_CHARS))
            }
            if (emotionalGuidance.isNotBlank()) {
                append("\n\nCONVERSATIONAL CARE GUIDANCE:\n")
                append(emotionalGuidance.take(MAX_EMOTIONAL_GUIDANCE_CHARS))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        history.takeLast(MAX_HISTORY_MESSAGES).forEach { (role, text) ->
            if (text.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", if (role == "assistant") "assistant" else "user")
                        .put("content", text.take(MAX_HISTORY_MESSAGE_CHARS))
                )
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage.take(MAX_USER_MESSAGE_CHARS)))

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("think", false)
            .put("keep_alive", "5m")
            .put("options", JSONObject().put("temperature", 0.45).put("num_predict", 320))

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                            ?: JSONObject(raw).optString("error")
                    } catch (_: Exception) { raw }
                    return@withContext Result(
                        false,
                        error = "Local Ollama returned HTTP ${response.code}: ${detail.ifBlank { "request failed" }}"
                    )
                }
                val text = try {
                    JSONObject(raw).optJSONObject("message")?.optString("content").orEmpty().trim()
                } catch (_: Exception) {
                    ""
                }
                if (text.isBlank()) Result(false, error = "Local Ollama returned an empty response")
                else Result(true, text)
            }
        } catch (error: Exception) {
            Result(
                false,
                error = when (error) {
                    is java.net.ConnectException, is java.net.UnknownHostException ->
                        "Local Ollama is not reachable at $endpoint. Start Ollama in Termux and make sure ${model} is installed."
                    else -> error.message ?: "Local Ollama request failed"
                }
            )
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434/api/chat"
        const val DEFAULT_MODEL = "qwen3:1.7b"
        private const val MAX_HISTORY_MESSAGES = 8
        private const val MAX_HISTORY_MESSAGE_CHARS = 1200
        private const val MAX_USER_MESSAGE_CHARS = 8000
        private const val MAX_MEMORY_CHARS = 4000
        private const val MAX_EMOTIONAL_GUIDANCE_CHARS = 600
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_PROMPT = """
You are Jarvis, a trusted personal AI assistant and friendly digital companion.
Personality: intelligent, warm, calm, lightly humorous, respectful and honest.
Languages: understand and respond naturally in Hindi, Marathi, English and Hinglish.
Use the same language style as the user. Keep ordinary spoken replies concise.
Use recent conversation and supplied personal memory only when relevant. Never invent
personal facts or claim to remember something that is not in the supplied memory.
You can explain, brainstorm, teach and help plan. Do not claim phone actions were
performed; device actions are handled separately by Jarvis's command executor.
For sensitive actions, ask for clear confirmation. If you do not know, say so.
""".trimIndent()
    }
}
