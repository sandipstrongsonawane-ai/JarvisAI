package com.jarvis.assistant.util

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskListManagerTest {
    private lateinit var context: Context
    private lateinit var tasks: TaskListManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jarvis_daily_tasks", Context.MODE_PRIVATE)
            .edit().clear().commit()
        tasks = TaskListManager(context)
    }

    @Test
    fun tasksPersistAcrossManagerInstances() {
        tasks.add("Pay electricity bill")
        assertEquals("Pay electricity bill", TaskListManager(context).pending().single().title)
    }

    @Test
    fun duplicatePendingTaskIsNotAddedTwice() {
        tasks.add("Buy milk")
        tasks.add("  buy   milk ")
        assertEquals(1, tasks.pending().size)
    }

    @Test
    fun taskCanBeCompletedByPendingListIndex() {
        tasks.add("Call office")
        val result = tasks.complete("1")
        assertEquals("Call office", result.task?.title)
        assertTrue(tasks.pending().isEmpty())
        assertEquals(1, tasks.completed().size)
    }

    @Test
    fun ambiguousPartialMatchDoesNotCompleteAnything() {
        tasks.add("Call office")
        tasks.add("Call electrician")
        val result = tasks.complete("Call")
        assertTrue(result.ambiguous)
        assertEquals(2, tasks.pending().size)
    }

    @Test
    fun dueDateAndPriorityPersistAcrossManagerInstances() {
        val dueAt = 1_800_000_000_000L
        tasks.add("Submit report", dueAt = dueAt, priority = "high")
        val saved = TaskListManager(context).pending().single()
        assertEquals(dueAt, saved.dueAt)
        assertEquals("high", saved.priority)
    }

    @Test
    fun invalidPriorityFallsBackToNormal() {
        tasks.add("Read book", priority = "urgent")
        assertEquals("normal", tasks.pending().single().priority)
    }

    @Test
    fun clearCompletedKeepsPendingTasks() {
        tasks.add("Task one")
        tasks.add("Task two")
        tasks.complete("1")
        assertEquals(1, tasks.clearCompleted())
        assertEquals("Task two", tasks.pending().single().title)
    }
}
