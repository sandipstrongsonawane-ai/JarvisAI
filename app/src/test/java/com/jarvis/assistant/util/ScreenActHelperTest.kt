package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenActHelperTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun emptyScreenProducesHelpfulAccessibilityGuidance() {
        val plan = ScreenActHelper.plan(context, "   ")
        assertEquals("empty", plan.summary)
        assertNull(plan.action)
        assertTrue(plan.spoken.contains("Enable Accessibility"))
    }

    @Test
    fun readIntentOnlySummarizesAndNeverExecutesDetectedLink() {
        val plan = ScreenActHelper.plan(
            context,
            "Invoice details https://example.com/pay",
            "read and summarize this"
        )
        assertNull(plan.action)
        assertTrue(plan.spoken.contains("Invoice details"))
        assertTrue(plan.spoken.contains("example.com"))
    }

    @Test
    fun explicitOpenIntentBuildsOpenUrlCommand() {
        val plan = ScreenActHelper.plan(
            context,
            "Visit https://example.com/help",
            "open this link"
        )
        assertEquals(ActionType.OPEN_URL.key, plan.action?.action)
        assertEquals("https://example.com/help", plan.action?.target)
    }

    @Test
    fun phoneNumberRequiresExplicitCallIntent() {
        val text = "Support number: +91 98765 43210"
        val readPlan = ScreenActHelper.plan(context, text, "read this")
        assertNull(readPlan.action)

        val callPlan = ScreenActHelper.plan(context, text, "call this number")
        assertEquals(ActionType.CALL.key, callPlan.action?.action)
        assertEquals("+919876543210", callPlan.action?.target)
    }

    @Test
    fun emailIntentOpensMailToOnlyWhenRequested() {
        val text = "Contact support@example.com"
        val readPlan = ScreenActHelper.plan(context, text, "summarize")
        assertNull(readPlan.action)

        val emailPlan = ScreenActHelper.plan(context, text, "email this address")
        assertEquals(ActionType.OPEN_URL.key, emailPlan.action?.action)
        assertEquals("mailto:support@example.com", emailPlan.action?.target)
    }
}
