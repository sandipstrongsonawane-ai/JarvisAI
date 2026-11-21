package com.jarvis.assistant.executor

import com.jarvis.assistant.model.AssistantCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskConfirmGateTest {
    @Test
    fun confirmationRequiresAnExactPhrase() {
        assertTrue(RiskConfirmGate.isConfirmSpeech("yes"))
        assertTrue(RiskConfirmGate.isConfirmSpeech("  Haan  "))
        assertTrue(RiskConfirmGate.isConfirmSpeech("go ahead"))
        assertFalse(RiskConfirmGate.isConfirmSpeech("yes send the message"))
        assertFalse(RiskConfirmGate.isConfirmSpeech("confirm the payment"))
        assertFalse(RiskConfirmGate.isConfirmSpeech("yes, please"))
    }

    @Test
    fun directPhoneCallRequiresConfirmationAndNamesContact() {
        val command = AssistantCommand("call", target = "Ramesh")
        assertTrue(RiskConfirmGate.isRisky(command))
        val prompt = RiskConfirmGate.promptFor(command)
        assertTrue(prompt.contains("Ramesh"))
        assertTrue(prompt.contains("confirm"))
    }

    @Test
    fun safeOpenAppChainDoesNotRequireSecondConfirmation() {
        val command = AssistantCommand(
            "multi_step",
            steps = listOf(
                AssistantCommand("open_app", target = "Chrome"),
                AssistantCommand("open_app", target = "Maps")
            )
        )
        assertFalse(RiskConfirmGate.isRisky(command))
    }

    @Test
    fun messageChainRequiresConfirmationAndShowsRecipientAndBody() {
        val command = AssistantCommand(
            "multi_step",
            steps = listOf(
                AssistantCommand("open_app", target = "WhatsApp"),
                AssistantCommand("whatsapp_message", target = "Rahul", message = "I am on my way")
            )
        )
        assertTrue(RiskConfirmGate.isRisky(command))
        val prompt = RiskConfirmGate.promptFor(command)
        assertTrue(prompt.contains("Rahul"))
        assertTrue(prompt.contains("I am on my way"))
    }
    @Test
    fun agentTaskWithExternalActionRequiresOneUpfrontConfirmation() {
        val command = AssistantCommand(
            "agent_task",
            target = "send WhatsApp message to Rahul and then open Maps"
        )
        assertTrue(RiskConfirmGate.isRisky(command))
        assertTrue(RiskConfirmGate.promptFor(command).contains("send WhatsApp message"))
    }

    @Test
    fun harmlessAgentTaskDoesNotRequireConfirmation() {
        assertFalse(
            RiskConfirmGate.isRisky(
                AssistantCommand("agent_task", target = "open Chrome and then open Maps")
            )
        )
    }


    @Test
    fun pendingRiskyCommandCanOnlyBeConsumedOnce() {
        RiskConfirmGate.cancel()
        val command = AssistantCommand("send_sms", target = "9876543210", message = "Test")
        RiskConfirmGate.hold(command)

        assertTrue(RiskConfirmGate.hasPending())
        assertTrue(RiskConfirmGate.takeIfConfirm("yes") == command)
        assertFalse(RiskConfirmGate.hasPending())
        assertTrue(RiskConfirmGate.takeIfConfirm("yes") == null)
    }


}
