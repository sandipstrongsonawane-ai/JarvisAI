package com.jarvis.assistant.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientHeaderMatchTest {
    @Test
    fun acceptsExactContactNameIgnoringCase() {
        assertTrue(JarvisAccessibilityService.recipientHeaderMatches("Rahul Sharma", "rahul sharma"))
    }

    @Test
    fun rejectsPartialNameCollision() {
        assertFalse(JarvisAccessibilityService.recipientHeaderMatches("Rajat Patil", "Raj"))
        assertFalse(JarvisAccessibilityService.recipientHeaderMatches("Rahul", "Rah"))
    }

    @Test
    fun acceptsExactPhoneAndCountryCodeVariant() {
        assertTrue(JarvisAccessibilityService.recipientHeaderMatches("+91 98765 43210", "9876543210"))
        assertTrue(JarvisAccessibilityService.recipientHeaderMatches("9876543210", "+91 98765 43210"))
    }

    @Test
    fun rejectsDifferentPhoneNumbersWithSharedDigits() {
        assertFalse(JarvisAccessibilityService.recipientHeaderMatches("+91 98765 43210", "987654321"))
    }

    @Test
    fun rejectsBlankValues() {
        assertFalse(JarvisAccessibilityService.recipientHeaderMatches("", "Rahul"))
        assertFalse(JarvisAccessibilityService.recipientHeaderMatches("Rahul", " "))
    }
}
