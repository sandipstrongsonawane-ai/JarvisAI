package com.jarvis.assistant.ai

import android.net.Uri
import com.jarvis.assistant.model.AssistantCommand
import com.jarvis.assistant.model.ActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKeyProvider: () -> String?) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val supportedActions = ActionType.entries
        .filter { it != ActionType.UNKNOWN }
        .joinToString(" | ") { it.key }

    private val systemPrompt = """
        You are the reasoning core of an Android voice assistant called Jarvis.
        The user may speak in English, Hindi, or a Hindi-English mix (Hinglish).
        Understand these languages and respond naturally in the user's language.
        Return ONLY a single JSON object, no prose or markdown fences, matching this schema:
        {
          "action": "$supportedActions",
          "target": "string or null",
          "message": "string or null",
          "extra": {"key": "value"}
        }
        Rules:
        - For conversation use action "reply" and put the spoken response in "message".
        - For open_app, target is the installed app name (e.g. WhatsApp, Camera).
        - For send_sms, target is contact name and message is body text.
        - For call, target is contact name.
        - For toggle_setting, target is wifi, bluetooth, flashlight, or airplane_mode.
        - For set_volume, target is up, down, mute, max, or a 0-100 number.
        - For media_control, target is play, pause, next, or previous.
        - For play_media, target is the song/artist/query; message may specify youtube, youtube music, or spotify.\n        - For navigate, target is the exact destination/place and message is driving, walking, cycling, or transit.
        - For web_search, target is the search query.
        - For set_alarm, target is 24-hour HH:mm.
        - For set_timer, target is total duration in seconds.
        - For open_vision, target is ocr, objects, or faces.
        - For lock_app/unlock_app, target is app name. For set_pin, target is a 4+ digit PIN.
        - For multiple actions use multi_step with ordered "steps", each item following this schema.
        - Never include commentary outside the JSON object.
    """.trimIndent()

    private val conversationHistory = mutableListOf<Pair<String, String>>()

    // models.list is relatively expensive and the enabled model catalogue rarely changes.
    // Cache per API-key fingerprint for six hours so voice replies do not wait for discovery
    // on every Gemini fallback request.
    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cachedApiKeyFingerprint: Int = 0
    @Volatile private var cachedModelsAtMs: Long = 0L
    private val modelCacheLock = Any()
    private val modelCacheTtlMs = TimeUnit.HOURS.toMillis(6)

    suspend fun getCommand(userSpeech: String): AssistantCommand = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return@withContext AssistantCommand(action = "reply", message = "Gemini API key missing. Add it in Settings.")
        }

        conversationHistory.add("user" to userSpeech)
        val requestBody = buildRequestBody()
        val models = availableModelsFor(apiKey)
        var lastError = "No Gemini model returned a response."

        for (model in models) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${Uri.encode(model)}:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("content-type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(400)
                        lastError = "HTTP ${response.code} from $model: $detail"
                        // Capacity/rate limits and stale or inaccessible model IDs should
                        // automatically fall through to another model from models.list.
                        if (response.code in retryableModelErrors) return@use
                        conversationHistory.removeAt(conversationHistory.lastIndex)
                        return@withContext AssistantCommand(
                            action = "reply",
                            message = "Gemini API error: ${response.code}: $detail"
                        )
                    }

                    val raw = response.body?.string().orEmpty()
                    try {
                        val text = extractResponseText(raw)
                        val command = parseCommandJson(text)
                        conversationHistory.add("assistant" to text)
                        pruneHistory()
                        return@withContext command
                    } catch (e: Exception) {
                        lastError = "Invalid response from $model: ${e.message.orEmpty()}"
                    }
                }
            } catch (e: IOException) {
                lastError = "Network error from $model: ${e.message.orEmpty()}"
            }
        }

        conversationHistory.removeAt(conversationHistory.lastIndex)
        AssistantCommand(
            action = "reply",
            message = "Gemini ke available models abhi busy ya unavailable hain. Last error: ${lastError.take(220)}"
        )
    }

    private fun buildRequestBody(): JSONObject {
        val contentsArray = JSONArray()
        conversationHistory.forEach { (role, text) ->
            contentsArray.put(JSONObject().apply {
                put("role", if (role == "assistant") "model" else "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        return JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", contentsArray)
            put("generationConfig", JSONObject().put("maxOutputTokens", 300))
        }
    }

    private fun availableModelsFor(apiKey: String): List<String> {
        val fingerprint = apiKey.hashCode()
        val now = System.currentTimeMillis()
        val current = cachedModels
        if (current.isNotEmpty() &&
            cachedApiKeyFingerprint == fingerprint &&
            now - cachedModelsAtMs < modelCacheTtlMs
        ) {
            return current
        }

        val startedAt = System.currentTimeMillis()
        val discovered = discoverAvailableModels(apiKey)
        android.util.Log.d(
            "GeminiClient",
            "Model discovery completed in ${System.currentTimeMillis() - startedAt}ms; models=${discovered.size}"
        )
        if (discovered.isNotEmpty()) {
            synchronized(modelCacheLock) {
                cachedModels = discovered
                cachedApiKeyFingerprint = fingerprint
                cachedModelsAtMs = System.currentTimeMillis()
            }
            return discovered
        }
        // Keep the known fallback path available if discovery is temporarily offline.
        return fallbackModels
    }

    /**
     * Fetch the models enabled for this API key. Only models advertising
     * generateContent are candidates; embedding/image/audio-only models are excluded.
     */
    private fun discoverAvailableModels(apiKey: String): List<String> {
        val discovered = linkedSetOf<String>()
        var pageToken: String? = null

        var pageCount = 0
        while (pageCount < 5) {
            pageCount++
            val builder = Uri.parse("https://generativelanguage.googleapis.com/v1beta/models")
                .buildUpon()
                .appendQueryParameter("pageSize", "100")
            if (!pageToken.isNullOrBlank()) builder.appendQueryParameter("pageToken", pageToken)

            val request = Request.Builder()
                .url(builder.build().toString())
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            val page = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                null
            } ?: break

            val models = page.optJSONArray("models") ?: JSONArray()
            for (index in 0 until models.length()) {
                val item = models.optJSONObject(index) ?: continue
                val fullName = item.optString("name")
                val modelId = fullName.substringAfterLast('/').trim()
                val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                val supportsGenerate = (0 until methods.length()).any {
                    methods.optString(it) == "generateContent"
                }
                if (modelId.startsWith("gemini-", ignoreCase = true) && supportsGenerate) {
                    discovered.add(modelId)
                }
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() && it != "null" }
            if (pageToken == null) break
        }

        return discovered.sortedWith(
            compareBy<String> { modelPriority(it) }
                .thenBy { if (it.contains("preview", ignoreCase = true) || it.contains("experimental", ignoreCase = true)) 1 else 0 }
                .thenBy { it }
        )
    }

    private fun modelPriority(model: String): Int {
        val id = model.lowercase()
        return when {
            id == "gemini-3.8-flash" -> 0
            id == "gemini-3.5-flash-lite" -> 1
            id == "gemini-3.7-flash" -> 2
            id.contains("flash") && id.contains("lite") -> 3
            id.contains("flash") -> 4
            id.contains("pro") -> 5
            else -> 6
        }
    }

    private fun extractResponseText(raw: String): String {
        val candidates = JSONObject(raw).optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini response has no candidates")
        for (index in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(index)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (partIndex in 0 until parts.length()) {
                val text = parts.optJSONObject(partIndex)?.optString("text").orEmpty().trim()
                if (text.isNotBlank()) {
                    return text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                }
            }
        }
        throw IllegalStateException("Gemini response contains no text")
    }

    private fun parseCommandJson(text: String): AssistantCommand {
        return try {
            val trimmed = text.trim()
            when {
                trimmed.startsWith("{") -> parseOne(JSONObject(trimmed))
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    when {
                        arr.length() >= 2 -> {
                            val steps = (0 until arr.length()).map { parseOne(arr.getJSONObject(it)) }
                            AssistantCommand(action = "multi_step", steps = steps)
                        }
                        arr.length() == 1 -> parseOne(arr.getJSONObject(0))
                        else -> throw IllegalArgumentException("Empty JSON array")
                    }
                }
                else -> throw IllegalArgumentException("Expected a JSON object or array")
            }
        } catch (e: Exception) {
            // Invalid or unsafe model output is a normal conversational fallback,
            // not an uncaught exception that can break the assistant command flow.
            AssistantCommand(
                action = "reply",
                message = "Could not parse Gemini command: ${e.message.orEmpty().take(180)}"
            )
        }
    }

    private fun parseOne(json: JSONObject): AssistantCommand {
        val extraObj = json.optJSONObject("extra")
        val extraMap = mutableMapOf<String, String>()
        extraObj?.keys()?.forEach { key -> extraMap[key] = extraObj.optString(key, "") }

        val stepsArray = json.optJSONArray("steps")
        val steps = if (stepsArray != null && stepsArray.length() > 0) {
            (0 until stepsArray.length()).map { parseOne(stepsArray.getJSONObject(it)) }
        } else null

        val action = json.optString("action", "reply").trim().lowercase()
        if (ActionType.fromKey(action) == ActionType.UNKNOWN) {
            throw IllegalArgumentException("Unsupported Gemini action: $action")
        }
        val target = json.optString("target", null).let { if (it == "null" || it.isNullOrBlank()) null else it }
        val message = json.optString("message", null).let { if (it == "null" || it.isNullOrBlank()) null else it }
        if (action == "multi_step" && steps.isNullOrEmpty()) {
            throw IllegalArgumentException("Gemini multi_step requires at least one step")
        }
        if (action != "multi_step" && steps != null) {
            throw IllegalArgumentException("Only multi_step may contain steps")
        }
        if (steps?.any { it.action == "multi_step" } == true) {
            throw IllegalArgumentException("Nested multi_step is not supported")
        }

        return AssistantCommand(action = action, target = target, message = message, extra = extraMap, steps = steps)
    }

    private fun pruneHistory(maxTurns: Int = 5) {
        while (conversationHistory.size > maxTurns * 2) conversationHistory.removeAt(0)
    }

    private companion object {
        val retryableModelErrors = setOf(400, 404, 408, 429, 500, 502, 503, 504)
        val fallbackModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash")
    }
}
