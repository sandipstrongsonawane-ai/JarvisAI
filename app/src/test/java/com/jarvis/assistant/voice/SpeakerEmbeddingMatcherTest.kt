package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEmbeddingMatcherTest {
    private fun vector(offset: Float = 0f): FloatArray =
        FloatArray(32) { index -> if (index == 0) 1f else if (index == 1) offset else 0f }

    @Test
    fun storesAndRestoresAllIndependentEnrollmentSamples() {
        val encoded = SpeakerEmbeddingMatcher.encode(
            listOf(vector(0.02f), vector(0.03f), vector(0.01f)), 0.60f
        )
        assertNotNull(encoded)
        val restored = SpeakerEmbeddingMatcher.decode(encoded!!)
        assertNotNull(restored)
        assertEquals(3, restored!!.size)
        assertTrue(SpeakerEmbeddingMatcher.matches(restored, vector(0.02f), 0.60f))
    }

    @Test
    fun rejectsEnrollmentWithInconsistentSpeakers() {
        val different = FloatArray(32) { index -> if (index == 1) 1f else 0f }
        assertFalse(
            SpeakerEmbeddingMatcher.encode(
                listOf(vector(), vector(0.01f), different), 0.60f
            ) != null
        )
    }

    @Test
    fun requiresMajorityAgreementAcrossStoredSamples() {
        val enrolled = listOf(vector(), vector(0.01f), vector(0.02f))
        assertTrue(SpeakerEmbeddingMatcher.matches(enrolled, vector(0.01f), 0.60f))

        val mostlyDifferent = listOf(vector(), FloatArray(32) { if (it == 1) 1f else 0f },
            FloatArray(32) { if (it == 2) 1f else 0f })
        assertFalse(SpeakerEmbeddingMatcher.matches(mostlyDifferent, vector(), 0.60f))
    }

    @Test
    fun readsLegacySingleEmbeddingFormat() {
        val legacy = vector().joinToString(",")
        val restored = SpeakerEmbeddingMatcher.decode(legacy)
        assertNotNull(restored)
        assertEquals(1, restored!!.size)
        assertTrue(SpeakerEmbeddingMatcher.matches(restored, vector(), 0.60f))
    }

    @Test
    fun rejectsMalformedOrNonFiniteStoredVectors() {
        assertEquals(null, SpeakerEmbeddingMatcher.decode("not,a,valid,vector"))
        val malformed = FloatArray(32) { if (it == 0) Float.NaN else 0f }.joinToString(",")
        assertEquals(null, SpeakerEmbeddingMatcher.decode(malformed))
    }
}
