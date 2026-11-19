package com.jarvis.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChallengeTest {
    @Test
    fun acceptsExactPhraseIgnoringCaseAndPunctuation() {
        assertTrue(VoiceChallenge.matches("amber birch copper", "Amber, birch! copper."))
    }

    @Test
    fun rejectsWrongOrExtraWords() {
        assertFalse(VoiceChallenge.matches("amber birch copper", "amber birch silver"))
        assertFalse(VoiceChallenge.matches("amber birch copper", "amber birch copper please"))
    }

    @Test
    fun rejectsBlankTranscript() {
        assertFalse(VoiceChallenge.matches("amber birch copper", ""))
        assertFalse(VoiceChallenge.matches("", "amber birch copper"))
    }
}
