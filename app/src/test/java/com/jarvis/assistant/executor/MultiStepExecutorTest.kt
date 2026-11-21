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
    fun stopsAndReportsUnverifiedWhenStepReturnsBlank() {
        var calls = 0
        val report = executor {
            calls++
            if (calls == 1) "Opened Chrome" else "   "
        }.execute(
            listOf(
                AssistantCommand("open_app", target = "Chrome"),
                AssistantCommand("open_app", target = "Maps")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(2, calls)
        assertTrue(report.summary.contains("No result returned at step 2 of 2"))
        assertTrue(report.summary.contains("completion is unverified"))
    }

    @Test
    fun treatsCannotLaunchResponseAsHardFailure() {
        var calls = 0
        val report = executor {
            calls++
            "Can't launch YouTube."
        }.execute(
            listOf(
                AssistantCommand("open_app", target = "YouTube"),
                AssistantCommand("play_media", target = "song", message = "youtube")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("Can't launch YouTube"))
    }

    @Test
    fun rejectsSequencesAboveSafetyLimit() {
        val steps = List(MultiStepExecutor.MAX_STEPS + 1) { index ->
            AssistantCommand("open_app", target = "App$index")
        }
        val report = executor().execute(steps)
        assertFalse(report.ok)
        assertTrue(report.summary.contains("maximum is"))
        assertTrue(report.stepResults.isEmpty())
    }

    @Test
    fun rejectsEmptySequence() {
        val report = executor().execute(emptyList(), paceMs = 0)
        assertFalse(report.ok)
        assertTrue(report.stepResults.isEmpty())
    }

    @Test
    fun parsesWhatsAppMessageOnlyWhenRecipientAndBodyArePresent() {
        val steps = executor().parseOfflineChain("open WhatsApp and send Hello to Rahul")
        assertEquals(2, steps?.size)
        assertEquals("open_app", steps?.get(0)?.action)
        assertEquals("WhatsApp", steps?.get(0)?.target)
        assertEquals("whatsapp_message", steps?.get(1)?.action)
        assertEquals("Rahul", steps?.get(1)?.target)
        assertEquals("Hello", steps?.get(1)?.message)
    }

    @Test
    fun doesNotPretendToSendWhatsAppMessageWithoutRecipient() {
        assertNull(executor().parseOfflineChain("open WhatsApp and send Hello Rahul"))
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
    fun rejectsUnsupportedTorchChainInsteadOfReturningPartialSteps() {
        assertNull(executor().parseOfflineChain("torch on and then send a message to Rahul"))
        assertNull(executor().parseOfflineChain("torch on and then increase volume"))
    }

    @Test
    fun stopsChainWhenRiskyStepIsWaitingForConfirmation() {
        var calls = 0
        val report = executor {
            calls++
            "Send the message to Rahul? Say confirm or cancel."
        }.execute(
            listOf(
                AssistantCommand("whatsapp_message", target = "Rahul", message = "Hello"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("step 1 of 2"))
        assertTrue(report.summary.contains("Say confirm or cancel"))
    }

    @Test
    fun stopsChainWhenActionRequiresManualContactSelection() {
        var calls = 0
        val report = executor {
            calls++
            "Opening WhatsApp for a voice call. Select the contact in the app, sir."
        }.execute(
            listOf(
                AssistantCommand("app_call", target = "WhatsApp", message = "voice"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("Select the contact in the app"))
    }

    @Test
    fun stopsChainWhenMessageNeedsManualSend() {
        var calls = 0
        val report = executor {
            calls++
            "WhatsApp opened with a prepared message for Rahul. Tap Send to send it; delivery is not verified."
        }.execute(
            listOf(
                AssistantCommand("whatsapp_message", target = "Rahul", message = "Hello"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("Tap Send to send it"))
    }

    @Test
    fun stopsChainWhenSpecialAccessRetryIsPending() {
        var calls = 0
        val report = executor {
            calls++
            "Jarvis needs Accessibility access. Enable Jarvis in the settings page; I'll retry the action when you return."
        }.execute(
            listOf(
                AssistantCommand("read_screen"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("retry the action when you return"))
    }

    @Test
    fun stopsChainWhenRuntimePermissionRetryIsPending() {
        var calls = 0
        val report = executor {
            calls++
            "Jarvis needs permission to complete this action. Please grant it from the permission prompt/notification; I'll retry automatically."
        }.execute(
            listOf(
                AssistantCommand("send_sms", target = "Rahul", message = "Hello"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("retry automatically"))
    }

    @Test
    fun stopsChainWhenTimerDurationIsInvalid() {
        var calls = 0
        val report = executor {
            calls++
            "Timer duration must be greater than zero."
        }.execute(
            listOf(
                AssistantCommand("set_timer", target = "0"),
                AssistantCommand("open_app", target = "Chrome")
            ),
            paceMs = 0
        )
        assertFalse(report.ok)
        assertEquals(1, calls)
        assertTrue(report.summary.contains("greater than zero"))
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
