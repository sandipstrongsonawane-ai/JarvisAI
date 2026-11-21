package com.voicute.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectionLogicTest {

    private fun frame(logic: DetectionLogic, now: Long): String? {
        val word = "Hey Jarvis"
        val probability = 0.92f
        val rms = 900f
        logic.record(probability, word, rms, now)
        return logic.evaluate(
            word,
            probability,
            rms,
            0.55f,
            3,
            0,
            now
        )
    }

    @Test
    fun requiresThreeConsecutiveFramesBeforeWake() {
        val logic = DetectionLogic()

        assertNull(frame(logic, 10_000L))
        assertNull(frame(logic, 10_030L))
        assertEquals("Hey Jarvis", frame(logic, 10_060L))
    }

    @Test
    fun cooldownBlocksImmediateRetriggerButAllowsLaterWake() {
        val logic = DetectionLogic()

        assertNull(frame(logic, 20_000L))
        assertNull(frame(logic, 20_030L))
        assertEquals("Hey Jarvis", frame(logic, 20_060L))

        assertNull(frame(logic, 20_090L))
        assertNull(frame(logic, 20_120L))
        assertNull(frame(logic, 20_150L))

        assertNull(frame(logic, 21_600L))
        assertNull(frame(logic, 21_630L))
        assertEquals("Hey Jarvis", frame(logic, 21_660L))
    }

    @Test
    fun resetClearsCooldownAndConsecutiveFrameHistory() {
        val logic = DetectionLogic()

        assertNull(frame(logic, 30_000L))
        assertNull(frame(logic, 30_030L))
        assertEquals("Hey Jarvis", frame(logic, 30_060L))

        logic.reset()

        assertNull(frame(logic, 30_090L))
        assertNull(frame(logic, 30_120L))
        assertEquals("Hey Jarvis", frame(logic, 30_150L))
    }
}
