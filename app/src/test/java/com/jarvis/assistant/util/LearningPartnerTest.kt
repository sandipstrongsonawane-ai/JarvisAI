package com.jarvis.assistant.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearningPartnerTest {
    private lateinit var context: Context
    private lateinit var store: LearningProgressStore
    private lateinit var parser: LearningPartner

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jarvis_learning_progress", Context.MODE_PRIVATE).edit().clear().commit()
        store = LearningProgressStore(context)
        parser = LearningPartner(context, com.jarvis.assistant.ai.OllamaClient())
    }

    @Test
    fun parsesExplicitLearningCommandsWithoutCapturingOrdinaryChat() {
        assertEquals(LearningPartner.Intent.Type.START, parser.parseIntent("Teach me Python")?.type)
        assertEquals("Python", parser.parseIntent("Teach me Python")?.topic)
        assertEquals(LearningPartner.Intent.Type.QUIZ, parser.parseIntent("Quiz me on Python")?.type)
        assertEquals("2 + 2 = 4", parser.parseIntent("Check my answer: 2 + 2 = 4")?.answer)
        assertEquals(LearningPartner.Intent.Type.PROGRESS, parser.parseIntent("my learning progress")?.type)
        assertEquals(LearningPartner.Intent.Type.STOP, parser.parseIntent("stop learning")?.type)
        assertNull(parser.parseIntent("Tell me a funny story"))
    }

    @Test
    fun storesPerTopicProgressAndComputesAccuracy() {
        store.startTopic("Python")
        store.recordLesson()
        store.recordLesson()
        store.recordQuiz(correct = true)
        store.recordQuiz(correct = false)

        val summary = store.progressSummary()
        assertTrue(summary.contains("Python"))
        assertTrue(summary.contains("2 lesson(s)"))
        assertTrue(summary.contains("2 quiz attempt(s)"))
        assertTrue(summary.contains("1 correct (50%)"))
    }

    @Test
    fun clearingSessionKeepsProgressButRemovesPendingQuestion() {
        store.startTopic("Math")
        store.recordLesson()
        store.savePendingQuestion("What is 2 + 2?")
        store.clearActiveSession()

        assertEquals("", store.activeTopic())
        assertEquals("", store.pendingQuestion())
        assertTrue(store.progressSummary().contains("Math"))
        assertTrue(store.progressSummary().contains("1 lesson(s)"))
    }
}
