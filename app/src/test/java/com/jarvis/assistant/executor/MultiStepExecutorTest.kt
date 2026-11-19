package com.jarvis.assistant.executor

import com.jarvis.assistant.ai.JarvisLlmClient

import android.content.ContextWrapper
import com.jarvis.assistant.model.AssistantCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStepExecutorTest {
    private fun executor(runOne: (AssistantCommand) -> String = { "OK" }) =
        MultiStepExecutor(ContextWrapper(null), runOne)

    @Test
    fun executesStepsInOrderAndReturnsAllResults() {
        val seen = mutableListOf<String>()
        val runner = executor { command ->
            seen += command.target.orEmpty()
            "Opened ${command.target}"
        }
        val report = runner.execute(
            listOf(
                AssistantCommand("open_app", target = "Chrome"),
                AssistantCommand("open_app", target = "Maps")
            ),
            paceMs = 0
        )
        assertTrue(report.ok)
        assertEquals(listOf("Chrome", "Maps"), seen)
        assertEquals(2, report.stepResults.size)
    }

    @Test
    fun stopsImmediatelyWhenAnActionReportsFailure() {
        var calls = 0
        val runner = executor {
            calls++
            if (calls == 1) "Permission required for this action." else "Should not run"
        }
        val report = runner.execute(
            listOf(
                AssistantCommand("toggle_setting", target = "wifi"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("step 1 of 2"))
    }

    @Test
    fun rejectsEmptySequence() {
        val report = executor().execute(emptyList(), paceMs = 0)
        assertFalse(report.ok)
        assertTrue(report.stepResults.isEmpty())
    }

    @Test
    fun parsesEnglishMultiActionOpenChain() {
        val steps = executor().parseOfflineChain("open Chrome and then open Maps")
        assertEquals(2, steps?.size)
        assertEquals("Chrome", steps?.get(0)?.target)
        assertEquals("Maps", steps?.get(1)?.target)
    }

    @Test
    fun parsesTorchOnThenOpenAppWithoutDroppingSecondAction() {
        val steps = executor().parseOfflineChain("torch on and then open Chrome")
        assertEquals(2, steps?.size)
        assertEquals("torch_on", steps?.get(0)?.target)
        assertEquals("Chrome", steps?.get(1)?.target)
    }

    @Test
    fun parsesOpenAppThenTorchOffInOrder() {
        val steps = executor().parseOfflineChain("open Chrome and then torch off")
        assertEquals(2, steps?.size)
        assertEquals("Chrome", steps?.get(0)?.target)
        assertEquals("torch_off", steps?.get(1)?.target)
    }

    @Test
    fun rejectsUnrecognizedSingleActionAsChain() {
        assertNull(executor().parseOfflineChain("what time is it"))
    }
    @Test
    fun rejectsStepsArrayOnSingleActionCommand() {
        val client = JarvisLlmClient(apiKeyProvider = { "test" })
        val method = JarvisLlmClient::class.java.getDeclaredMethod("parseAssistantCommand", String::class.java)
        method.isAccessible = true
        val parsed = method.invoke(
            client,
            """{"action":"open_app","target":"Chrome","steps":[{"action":"open_app","target":"Maps"}]}"""
        )
        assertNull(parsed)
    }

}
