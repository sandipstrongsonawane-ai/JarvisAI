package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechNormalizerTest {
    @Test
    fun normalizesCommonWakeWordRecognitionVariants() {
        assertEquals("hey jarvis", SpeechNormalizer.normalize("A Jarvis"))
        assertEquals("hey jarvis", SpeechNormalizer.normalize("Hay Jarvis"))
        assertEquals("hey jarvis", SpeechNormalizer.normalize("HEY JARWIS!"))
    }

    @Test
    fun normalizesHinglishDeviceActionAliases() {
        assertEquals("flashlight on", SpeechNormalizer.normalize("torch on"))
        assertEquals("flashlight off", SpeechNormalizer.normalize("torch bujhao"))
        assertEquals("volume up", SpeechNormalizer.normalize("awaaz badhao"))
        assertEquals("volume down", SpeechNormalizer.normalize("awaaz kam"))
    }

    @Test
    fun normalizesScreenAndSearchRecognitionErrors() {
        assertEquals("analyze my screen", SpeechNormalizer.normalize("analyse ice cream"))
        assertEquals("what's on my screen", SpeechNormalizer.normalize("what is on the screen"))
        assertEquals("search for weather", SpeechNormalizer.normalize("serch for weather"))
    }

    @Test
    fun normalizesWhitespaceAndPunctuation() {
        assertEquals("open chrome", SpeechNormalizer.normalize("  OPEN   CHROME?! "))
    }

    @Test
    fun leavesUnknownSpeechIntactApartFromBasicNormalization() {
        assertEquals("play brown rang", SpeechNormalizer.normalize("  Play Brown Rang  "))
    }
}
