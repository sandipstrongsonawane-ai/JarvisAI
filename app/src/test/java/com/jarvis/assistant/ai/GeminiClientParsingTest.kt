package com.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientParsingTest {
    private fun parse(json: String): com.jarvis.assistant.model.AssistantCommand {
        val client = GeminiClient { "test-key" }
        val method = GeminiClient::class.java.getDeclaredMethod("parseCommandJson", String::class.java)
        method.isAccessible = true
        return method.invoke(client, json) as com.jarvis.assistant.model.AssistantCommand
    }

    @Test
    fun acceptsOrderedMultiStepCommand() {
        val command = parse("""{"action":"multi_step","steps":[{"action":"open_app","target":"Chrome"},{"action":"open_app","target":"Maps"}]}""")
        assertEquals("multi_step", command.action)
        assertEquals(listOf("Chrome", "Maps"), command.steps?.map { it.target })
    }

    @Test
    fun rejectsStepsOnSingleAction() {
        val command = parse("""{"action":"open_app","target":"Chrome","steps":[{"action":"open_app","target":"Maps"}]}""")
        assertEquals("reply", command.action)
        assertTrue(command.message.orEmpty().contains("parse", ignoreCase = true))
    }

    @Test
    fun rejectsEmptyMultiStep() {
        val command = parse("""{"action":"multi_step","steps":[]}""")
        assertEquals("reply", command.action)
    }

    @Test
    fun rejectsNestedMultiStep() {
        val command = parse("""{"action":"multi_step","steps":[{"action":"multi_step","steps":[{"action":"open_app","target":"Chrome"}]}]}""")
        assertEquals("reply", command.action)
    }

    @Test
    fun rejectsUnknownAction() {
        val command = parse("""{"action":"delete_everything","target":"all"}""")
        assertEquals("reply", command.action)
    }
}
