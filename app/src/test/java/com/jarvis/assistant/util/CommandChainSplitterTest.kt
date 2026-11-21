package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandChainSplitterTest {
    @Test
    fun splitsMixedEnglishAndHindiConnectorsInSpokenOrder() {
        assertEquals(
            listOf("open Chrome", "turn torch on", "set volume up", "open Maps"),
            CommandChainSplitter.split("open Chrome and then turn torch on phir set volume up aur open Maps")
        )
    }

    @Test
    fun splitsAndAlsoAndPlusConnectors() {
        assertEquals(
            listOf("open Chrome", "open Maps", "turn torch on"),
            CommandChainSplitter.split("open Chrome and also open Maps plus turn torch on")
        )
    }

    @Test
    fun leavesOrdinarySingleRequestUnsplit() {
        assertNull(CommandChainSplitter.split("what is the weather today"))
        assertFalse(CommandChainSplitter.looksLikeChain("what is the weather today"))
    }

    @Test
    fun identifiesNaturalCallAndMessageChain() {
        assertTrue(CommandChainSplitter.looksLikeChain("call Rahul and text Amit"))
        assertEquals(
            listOf("call Rahul", "text Amit"),
            CommandChainSplitter.split("call Rahul and text Amit")
        )
    }
    @Test
    fun splitsDeviceActionsThatUsePlainAndConnector() {
        assertEquals(
            listOf("set alarm for 7 am", "increase volume", "open Maps"),
            CommandChainSplitter.split("set alarm for 7 am and increase volume aur open Maps")
        )
    }

}
