package com.jarvis.assistant.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.memory.PersistentMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistentMemoryTest {
    private lateinit var context: Context
    private lateinit var memory: PersistentMemory

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE).edit().clear().commit()
        memory = PersistentMemory(context)
    }

    @Test
    fun explicitFactIsSavedAndCanBeRecalledAfterNewInstance() {
        memory.rememberFact("I prefer short Hinglish replies")

        val restored = PersistentMemory(context)
        assertEquals(listOf("I prefer short Hinglish replies"), restored.getFacts())
    }

    @Test
    fun duplicateFactsAreNotAddedTwice() {
        memory.rememberFact("I like concise answers")
        memory.rememberFact("I like concise answers")

        assertEquals(1, memory.getFacts().size)
    }

    @Test
    fun clearRemovesStoredPersonalFacts() {
        memory.rememberFact("I prefer Hindi")
        memory.clear()

        assertTrue(PersistentMemory(context).getFacts().isEmpty())
    }
}
