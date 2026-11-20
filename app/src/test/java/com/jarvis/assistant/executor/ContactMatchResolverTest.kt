package com.jarvis.assistant.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatchResolverTest {
    private val contacts = listOf(
        ContactMatchResolver.Contact("Raj", "1111111111"),
        ContactMatchResolver.Contact("Rajesh", "2222222222"),
        ContactMatchResolver.Contact("Rajat Patil", "3333333333"),
        ContactMatchResolver.Contact("Anil Markhale", "4444444444"),
        ContactMatchResolver.Contact("Anil Waghmare", "5555555555")
    )

    @Test
    fun exactNameOverridesAmbiguousPrefix() {
        assertEquals("1111111111", ContactMatchResolver.resolve("Raj", contacts))
        assertEquals("2222222222", ContactMatchResolver.resolve("Rajesh", contacts))
    }

    @Test
    fun ambiguousPrefixReturnsNullRatherThanWrongContact() {
        assertNull(ContactMatchResolver.resolve("Ra", contacts))
        assertNull(ContactMatchResolver.resolve("Anil", contacts))
    }

    @Test
    fun uniqueTokenPrefixAndCaseInsensitiveExactMatchWork() {
        assertEquals("4444444444", ContactMatchResolver.resolve("Mark", contacts))
        assertEquals("3333333333", ContactMatchResolver.resolve("RAJAT PATIL", contacts))
    }
}
