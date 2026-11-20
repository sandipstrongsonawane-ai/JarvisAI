package com.jarvis.assistant.executor

import com.jarvis.assistant.model.AssistantCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskConfirmGateTest {
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
}
