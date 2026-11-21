package com.jarvis.assistant.executor

import com.jarvis.assistant.ai.OllamaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiActionPlannerTest {
    private val planner = MultiActionPlanner(OllamaClient())

    @Test
    fun parsesOrderedSafeActions() {
        val result = planner.parsePlan(
            """{"steps":[{"action":"open_app","target":"Chrome"},{"action":"web_search","target":"weather tomorrow"}]}"""
        )
        assertTrue(result.ok)
        assertEquals(listOf("open_app", "web_search"), result.steps.map { it.action })
        assertEquals(listOf("Chrome", "weather tomorrow"), result.steps.map { it.target })
    }

    @Test
    fun acceptsJsonWrappedInMarkdownFence() {
        val result = planner.parsePlan(
            """```json
{"steps":[{"action":"open_app","target":"Chrome"},{"action":"open_app","target":"Maps"}]}
```"""
        )
        assertTrue(result.ok)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun rejectsSensitiveMessagingAction() {
        val result = planner.parsePlan(
            """{"steps":[{"action":"open_app","target":"WhatsApp"},{"action":"whatsapp_message","target":"Rahul","message":"Hi"}]}"""
        )
        assertFalse(result.ok)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun rejectsMissingRequiredTarget() {
        val result = planner.parsePlan(
            """{"steps":[{"action":"open_app"},{"action":"web_search","target":"weather"}]}"""
        )
        assertFalse(result.ok)
    }

    @Test
    fun rejectsUnexpectedFieldsAndUnsafeUrlSchemes() {
        val extraField = """{"steps":[{"action":"open_app","target":"Chrome","admin":true},{"action":"web_search","target":"weather"}]}"""
        val unsafeUrl = """{"steps":[{"action":"open_url","target":"intent://settings"},{"action":"read_screen"}]}"""
        assertFalse(planner.parsePlan(extraField).ok)
        assertFalse(planner.parsePlan(unsafeUrl).ok)
    }

    @Test
    fun rejectsTooManyStepsAndMalformedJson() {
        val tooMany = """{"steps":[{"action":"read_screen"},{"action":"screenshot"},{"action":"briefing"},{"action":"recall"},{"action":"debrief"},{"action":"read_screen"}]}"""
        assertFalse(planner.parsePlan(tooMany).ok)
        assertFalse(planner.parsePlan("not json").ok)
    }

    @Test
    fun recognizesExplicitMultiActionRequestsButNotOrdinaryChat() {
        assertTrue(planner.looksLikeMultiActionRequest("Open Chrome and then search weather"))
        assertTrue(planner.looksLikeMultiActionRequest("Chrome kholo phir Maps kholo"))
        assertFalse(planner.looksLikeMultiActionRequest("Tell me a funny story"))
        assertFalse(planner.looksLikeMultiActionRequest("Open Chrome"))
    }
}
