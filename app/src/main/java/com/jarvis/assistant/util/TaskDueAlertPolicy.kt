package com.jarvis.assistant.util

/** Pure selection rule for proactive task reminders; safe to exercise without Android services. */
internal object TaskDueAlertPolicy {
    fun dueForAlert(
        tasks: List<TaskListManager.Task>,
        nowMs: Long,
        windowMs: Long
    ): List<TaskListManager.Task> = tasks.filter { task ->
        !task.isComplete && task.dueAt != null &&
            task.dueAt > 0L && task.dueAt - nowMs <= windowMs
    }
}
