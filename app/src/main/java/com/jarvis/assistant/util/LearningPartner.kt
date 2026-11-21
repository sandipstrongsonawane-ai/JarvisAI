package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.ai.OllamaClient

/**
 * Explicit, on-device learning sessions. Nothing is inferred or tracked in the background:
 * a session starts only when the user asks, and progress stays in private app preferences.
 */
class LearningPartner(
    context: Context,
    private val ollama: OllamaClient
) {
    private val store = LearningProgressStore(context)

    data class Intent(val type: Type, val topic: String = "", val answer: String = "") {
        enum class Type { START, QUIZ, CHECK_ANSWER, PROGRESS, STOP }
    }

    fun parseIntent(speech: String): Intent? {
        val text = speech.trim()
        val lower = text.lowercase()
        if (listOf("learning progress", "my learning progress", "meri learning progress",
                "meri padhai progress", "learning stats").any { lower == it || lower.contains(it) }) {
            return Intent(Intent.Type.PROGRESS)
        }
        if (listOf("stop learning", "end learning session", "learning band karo", "padhai band karo")
                .any { lower == it }) return Intent(Intent.Type.STOP)

        val answer = Regex(
            "^(?:check my answer|evaluate my answer|mera jawab check karo|answer check karo)\\s*[:,-]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.trim()
        if (!answer.isNullOrBlank()) return Intent(Intent.Type.CHECK_ANSWER, answer = answer)

        val quiz = Regex(
            "^(?:quiz me|test me|mera quiz lo|mera test lo)(?:\\s+(?:on|about))?\\s*(.*)$",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (quiz != null) return Intent(Intent.Type.QUIZ, topic = quiz.groupValues[1].trim())

        val startPatterns = listOf(
            Regex("^(?:teach me|start learning|learn about)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^mujhe\\s+(.+?)\\s+(?:sikhao|sikhna sikhao)$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+sikhna hai$", RegexOption.IGNORE_CASE)
        )
        for (pattern in startPatterns) {
            val topic = pattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (topic.isNotBlank()) return Intent(Intent.Type.START, topic = topic)
        }
        return null
    }

    suspend fun handle(speech: String): String? {
        val intent = parseIntent(speech) ?: return null
        return when (intent.type) {
            Intent.Type.START -> teach(intent.topic)
            Intent.Type.QUIZ -> quiz(intent.topic)
            Intent.Type.CHECK_ANSWER -> checkAnswer(intent.answer)
            Intent.Type.PROGRESS -> store.progressSummary()
            Intent.Type.STOP -> {
                store.clearActiveSession()
                "Learning session stopped. Your saved progress is still on this device."
            }
        }
    }

    private suspend fun teach(topic: String): String {
        if (topic.length > MAX_TOPIC_CHARS) return "Topic thoda short batao, please."
        val result = ollama.chat(
            "Teach the user about: ${topic.take(MAX_TOPIC_CHARS)}. Use the user's language. " +
                "Give one beginner-friendly micro-lesson with at most 3 short points, one practical example, " +
                "and exactly one small practice task. Be accurate; if uncertain, say so. Keep it concise."
        )
        if (!result.ok) return "Learning lesson nahi bana paya. ${result.error ?: "Check local Ollama."}"
        store.startTopic(topic)
        store.recordLesson()
        return result.text
    }

    private suspend fun quiz(requestedTopic: String): String {
        val topic = requestedTopic.ifBlank { store.activeTopic() }
        if (topic.isBlank()) return "Pehle bolo: Teach me [topic]."
        if (topic.length > MAX_TOPIC_CHARS) return "Topic thoda short batao, please."
        val result = ollama.chat(
            "Create exactly one clear quiz question about: ${topic.take(MAX_TOPIC_CHARS)}. " +
                "Use the user's language. Do not reveal the answer. Ask the user to reply starting with " +
                "'Check my answer:'. Keep it short."
        )
        if (!result.ok) return "Quiz question nahi bana paya. ${result.error ?: "Check local Ollama."}"
        store.startTopic(topic)
        store.savePendingQuestion(result.text)
        return result.text
    }

    private suspend fun checkAnswer(answer: String): String {
        val topic = store.activeTopic()
        val question = store.pendingQuestion()
        if (topic.isBlank() || question.isBlank()) return "Pehle topic par quiz lo: Quiz me on [topic]."
        val result = ollama.chat(
            "Evaluate this learner answer. Topic: ${topic.take(MAX_TOPIC_CHARS)}. " +
                "Question: ${question.take(MAX_QUESTION_CHARS)}. Answer: ${answer.take(MAX_ANSWER_CHARS)}. " +
                "Start your response with exactly [CORRECT] or [INCORRECT], then briefly explain in the user's language. " +
                "Be fair; accept equivalent wording. If the answer is partly right, use [INCORRECT] and explain what is missing."
        )
        if (!result.ok) return "Answer check nahi ho paya. ${result.error ?: "Check local Ollama."}"
        val marker = result.text.trimStart().take(12)
        if (marker.startsWith("[CORRECT]")) store.recordQuiz(correct = true)
        else if (marker.startsWith("[INCORRECT]")) store.recordQuiz(correct = false)
        store.clearPendingQuestion()
        return result.text.removePrefix("[CORRECT]").removePrefix("[INCORRECT]").trim()
    }

    companion object {
        private const val MAX_TOPIC_CHARS = 100
        private const val MAX_QUESTION_CHARS = 700
        private const val MAX_ANSWER_CHARS = 1000
    }
}

/** Private progress store, separate from Jarvis's personal-memory facts. */
internal class LearningProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_learning_progress", Context.MODE_PRIVATE)

    fun activeTopic(): String = prefs.getString(KEY_ACTIVE_TOPIC, "").orEmpty()
    fun startTopic(topic: String) { prefs.edit().putString(KEY_ACTIVE_TOPIC, topic.trim()).apply() }
    fun clearActiveSession() { prefs.edit().remove(KEY_ACTIVE_TOPIC).remove(KEY_PENDING_QUESTION).apply() }
    fun savePendingQuestion(question: String) { prefs.edit().putString(KEY_PENDING_QUESTION, question).apply() }
    fun pendingQuestion(): String = prefs.getString(KEY_PENDING_QUESTION, "").orEmpty()
    fun clearPendingQuestion() { prefs.edit().remove(KEY_PENDING_QUESTION).apply() }

    fun recordLesson() = increment(key("lessons"))
    fun recordQuiz(correct: Boolean) {
        increment(key("quiz_attempts"))
        if (correct) increment(key("correct_answers"))
    }

    fun progressSummary(): String {
        val topic = activeTopic()
        if (topic.isBlank()) return "Abhi koi active learning topic nahi hai. Bolo: Teach me [topic]."
        val lessons = prefs.getInt(key("lessons"), 0)
        val attempts = prefs.getInt(key("quiz_attempts"), 0)
        val correct = prefs.getInt(key("correct_answers"), 0)
        val accuracy = if (attempts == 0) 0 else (correct * 100 / attempts)
        return "Learning progress — $topic: $lessons lesson(s), $attempts quiz attempt(s), $correct correct ($accuracy%)."
    }

    private fun increment(name: String) { prefs.edit().putInt(name, prefs.getInt(name, 0) + 1).apply() }
    private fun key(metric: String): String = "topic:${activeTopic().lowercase()}:$metric"

    companion object {
        private const val KEY_ACTIVE_TOPIC = "active_topic"
        private const val KEY_PENDING_QUESTION = "pending_question"
    }
}
