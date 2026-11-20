package com.jarvis.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChallengeTest {
    @Test
    fun acceptsExactPhraseIgnoringCaseAndPunctuation() {
        assertTrue(VoiceChallenge.matches("amber birch copper delta", "Amber, birch! copper delta."))
    }

    @Test
    fun toleratesOneOfflineAsrWordErrorButRejectsMultipleErrors() {
        assertTrue(VoiceChallenge.matches("amber birch copper delta", "amber birch silver delta"))
        assertFalse(VoiceChallenge.matches("amber birch copper delta", "amber silver tiger delta"))
        assertFalse(VoiceChallenge.matches("amber birch copper delta", "amber birch copper delta please"))
    }

    @Test
    fun rejectsBlankTranscript() {
        assertFalse(VoiceChallenge.matches("amber birch copper", ""))
        assertFalse(VoiceChallenge.matches("", "amber birch copper delta"))
    }
}
