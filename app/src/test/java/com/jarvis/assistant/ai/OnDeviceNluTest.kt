package com.jarvis.assistant.ai

import com.jarvis.assistant.model.AssistantCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnDeviceNluTest {
    private val nlu by lazy { OnDeviceNlu(RuntimeEnvironment.getApplication()) }

    @Test
    fun classifiesNaturalLanguageOpenAppRequests() {
        assertEquals(AssistantCommand("open_app", "YouTube"), nlu.classify("Can you open YouTube"))
        assertEquals(AssistantCommand("open_app", "Camera"), nlu.classify("Please open Camera"))
        assertEquals(AssistantCommand("open_app", "Settings"), nlu.classify("Could you open Settings"))
    }

    @Test
    fun classifiesSearchRequestsAndPreservesQuery() {
        assertEquals(AssistantCommand("web_search", "weather tomorrow"), nlu.classify("Can you search for weather tomorrow"))
        assertEquals(AssistantCommand("web_search", "nearby hospitals"), nlu.classify("Look up nearby hospitals"))
    }

    @Test
    fun extractsMediaQueryAndRequestedApp() {
        assertEquals(
            AssistantCommand("play_media", "Kesariya", "spotify"),
            nlu.classify("Put on Kesariya on Spotify")
        )
        assertEquals(
            AssistantCommand("play_media", "lofi beats", "youtube music"),
            nlu.classify("Put on lofi beats on YouTube Music")
        )
        assertEquals(
            AssistantCommand("play_media", "old Hindi songs", "youtube"),
            nlu.classify("Put on old Hindi songs on YouTube")
        )
    }

    @Test
    fun rejectsBlankShortAndUnsupportedRequests() {
        assertNull(nlu.classify(""))
        assertNull(nlu.classify("hi"))
        assertNull(nlu.classify("tell me a joke"))
        assertNull(nlu.classify("can you open"))
        assertNull(nlu.classify("look up"))
    }

    @Test
    fun leavesUnrecognizedMediaRequestUnclassifiedWhenQueryIsMissing() {
        assertNull(nlu.classify("put on music"))
    }
}
