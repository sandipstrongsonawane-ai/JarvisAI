package com.jarvis.assistant.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device To-Do list for Daily Life Manager.
 * Tasks survive process restarts and never leave the device.
 */
class TaskListManager(context: Context) {
    data class Task(
        val id: Long,
        val title: String,
        val createdAt: Long,
        val completedAt: Long? = null,
        val dueAt: Long? = null,
        val priority: String = "normal"
    ) {
        val isComplete: Boolean get() = completedAt != null
    }

    data class CompletionResult(val task: Task? = null, val ambiguous: Boolean = false)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(title: String, dueAt: Long? = null, priority: String = "normal"): Task? {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LENGTH)
        val cleanPriority = priority.lowercase().takeIf { it in setOf("low", "normal", "high") } ?: "normal"
        if (clean.isBlank()) return null
        val current = all()
        if (current.any { !it.isComplete && it.title.equals(clean, ignoreCase = true) }) {
            return current.first { !it.isComplete && it.title.equals(clean, ignoreCase = true) }
        }
        val task = Task(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
            title = clean,
            createdAt = System.currentTimeMillis(),
            dueAt = dueAt,
            priority = cleanPriority
        )
        save(current + task)
        return task
    }

    @Synchronized
    fun pending(): List<Task> = all().filterNot { it.isComplete }

    @Synchronized
    fun completed(): List<Task> = all().filter { it.isComplete }

    @Synchronized
    fun complete(reference: String): CompletionResult {
        val open = pending()
        if (open.isEmpty()) return CompletionResult()
        val query = reference.trim()
        val byIndex = query.toIntOrNull()?.let { index ->
            if (index in 1..open.size) listOf(open[index - 1]) else emptyList()
        }
        val matches = byIndex ?: open.filter { it.title.equals(query, true) }.ifEmpty {
            open.filter { it.title.contains(query, true) || query.contains(it.title, true) }
        }
        if (matches.size != 1) return CompletionResult(ambiguous = matches.size > 1)
        val target = matches.single().copy(completedAt = System.currentTimeMillis())
        save(all().map { if (it.id == target.id) target else it })
        return CompletionResult(task = target)
    }

    @Synchronized
    fun delete(reference: String): CompletionResult {
        val open = pending()
        if (open.isEmpty()) return CompletionResult()
        val query = reference.trim()
        val byIndex = query.toIntOrNull()?.let { index ->
            if (index in 1..open.size) listOf(open[index - 1]) else emptyList()
        }
        val matches = byIndex ?: open.filter { it.title.equals(query, true) }.ifEmpty {
            open.filter { it.title.contains(query, true) || query.contains(it.title, true) }
        }
        if (matches.size != 1) return CompletionResult(ambiguous = matches.size > 1)
        val target = matches.single()
        save(all().filterNot { it.id == target.id })
        return CompletionResult(task = target)
    }

    @Synchronized
    fun clearCompleted(): Int {
        val current = all()
        val remaining = current.filterNot { it.isComplete }
        save(remaining)
        return current.size - remaining.size
    }

    private fun all(): List<Task> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", -1L)
                val title = item.optString("title").trim()
                if (id <= 0L || title.isBlank()) return@mapNotNull null
                val completed = item.optLong("completedAt", 0L).takeIf { it > 0L }
                Task(
                    id, title, item.optLong("createdAt", 0L), completed,
                    item.optLong("dueAt", 0L).takeIf { it > 0L },
                    item.optString("priority", "normal").lowercase().takeIf { it in setOf("low", "normal", "high") } ?: "normal"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(tasks: List<Task>) {
        val array = JSONArray()
        tasks.takeLast(MAX_TASKS).forEach { task ->
            array.put(JSONObject()
                .put("id", task.id)
                .put("title", task.title)
                .put("createdAt", task.createdAt)
                .put("completedAt", task.completedAt ?: 0L)
                .put("dueAt", task.dueAt ?: 0L)
                .put("priority", task.priority))
        }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "jarvis_daily_tasks"
        private const val KEY_TASKS = "tasks"
        private const val MAX_TASKS = 100
        private const val MAX_TITLE_LENGTH = 120
    }
}
