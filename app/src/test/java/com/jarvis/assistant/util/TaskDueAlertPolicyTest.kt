package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDueAlertPolicyTest {
    @Test
    fun selectsTasksDueWithinThirtyMinutesAndOverdue() {
        val now = 1_800_000_000_000L
        val tasks = listOf(
            TaskListManager.Task(1, "Soon", now, dueAt = now + 10 * 60_000L),
            TaskListManager.Task(2, "Overdue", now, dueAt = now - 5 * 60_000L),
            TaskListManager.Task(3, "Later", now, dueAt = now + 45 * 60_000L),
            TaskListManager.Task(4, "No due date", now),
            TaskListManager.Task(5, "Completed", now, completedAt = now, dueAt = now)
        )

        assertEquals(
            listOf(1L, 2L),
            TaskDueAlertPolicy.dueForAlert(tasks, now, 30 * 60_000L).map { it.id }
        )
    }

    @Test
    fun excludesTasksWithNoValidDueTimestamp() {
        val tasks = listOf(
            TaskListManager.Task(1, "No due", 1L),
            TaskListManager.Task(2, "Invalid due", 1L, dueAt = 0L)
        )

        assertEquals(emptyList<TaskListManager.Task>(), TaskDueAlertPolicy.dueForAlert(tasks, 100L, 30_000L))
    }
}
