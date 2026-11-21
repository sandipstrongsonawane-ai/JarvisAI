package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionalIntelligenceTest {
    @Test
    fun recognizesHindiAndHinglishDistressCues() {
        assertEquals(EmotionalIntelligence.Tone.DISTRESSED, EmotionalIntelligence.assess("Aaj bahut tension hai").tone)
        assertEquals(EmotionalIntelligence.Tone.DISTRESSED, EmotionalIntelligence.assess("Main bahut udaas hoon").tone)
    }

    @Test
    fun recognizesFrustrationAndPositiveCues() {
        assertEquals(EmotionalIntelligence.Tone.FRUSTRATED, EmotionalIntelligence.assess("I am frustrated").tone)
        assertEquals(EmotionalIntelligence.Tone.POSITIVE, EmotionalIntelligence.assess("Aaj mast feel ho raha hai").tone)
    }

    @Test
    fun neutralInputDoesNotAddPromptOverhead() {
        assertEquals(EmotionalIntelligence.Tone.NEUTRAL, EmotionalIntelligence.assess("Open Chrome").tone)
        assertTrue(EmotionalIntelligence.guidanceFor("Open Chrome").isEmpty())
    }

    @Test
    fun guidanceIsSupportiveWithoutClaimingDiagnosis() {
        val guidance = EmotionalIntelligence.guidanceFor("I feel lonely")
        assertTrue(guidance.contains("non-judgmental"))
        assertTrue(guidance.contains("without diagnosing"))
        assertFalse(guidance.contains("You are depressed"))
    }

    @Test
    fun blankInputIsNeutral() {
        assertEquals(EmotionalIntelligence.Tone.NEUTRAL, EmotionalIntelligence.assess("   ").tone)
    }
}
