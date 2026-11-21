package com.jarvis.assistant.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisReplyRouterTest {
    private fun offlineOnlyRouter(
        handler: suspend (String) -> JarvisReplyRouter.OfflineOutcome
    ) = JarvisReplyRouter(
        llm = JarvisLlmClient(apiKeyProvider = { null }),
        offlineHandle = handler
    )

    @Test
    fun blankInputReturnsFallbackWithoutCallingOfflineHandler() = runBlocking {
        var called = false
        val router = offlineOnlyRouter {
            called = true
            JarvisReplyRouter.OfflineOutcome.NotACommand
        }

        val reply = router.reply("   ")

        assertEquals("I didn’t catch that, sir.", reply.text)
        assertEquals(JarvisReplyRouter.Reply.Source.FALLBACK, reply.source)
        assertFalse(called)
    }

    @Test
    fun offlineCommandIsReturnedWithoutCallingCloudLlm() = runBlocking {
        val router = offlineOnlyRouter {
            assertEquals("turn on flashlight", it)
            JarvisReplyRouter.OfflineOutcome.Handled("Flashlight on.")
        }

        val reply = router.reply("turn on flashlight")

        assertEquals("Flashlight on.", reply.text)
        assertEquals(JarvisReplyRouter.Reply.Source.OFFLINE_COMMAND, reply.source)
    }

    @Test
    fun offlineCommandHistoryCanBeClearedAndNextCommandStillWorks() = runBlocking {
        var count = 0
        val router = offlineOnlyRouter {
            count++
            JarvisReplyRouter.OfflineOutcome.Handled("Done $count")
        }

        assertEquals("Done 1", router.reply("first").text)
        router.clearHistory()
        assertEquals("Done 2", router.reply("second").text)
        assertEquals(2, count)
    }
}
