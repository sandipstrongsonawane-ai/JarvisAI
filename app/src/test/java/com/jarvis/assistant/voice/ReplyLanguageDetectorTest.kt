package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyLanguageDetectorTest {

    @Test
    fun detectsMarathiInDevanagari() {
        assertEquals("mr", ReplyLanguageDetector.detect("मला मदत करा, हे करूया.").language)
    }

    @Test
    fun detectsHindiInDevanagari() {
        assertEquals("hi", ReplyLanguageDetector.detect("मुझे बताओ, यह कैसे करना है?").language)
    }

    @Test
    fun detectsRomanMarathiAndMaharashtraHinglish() {
        assertEquals("mr", ReplyLanguageDetector.detect("Mala sang, ata kay karuya?").language)
        assertEquals("mr", ReplyLanguageDetector.detect("Majha phone kuthe aahe?").language)
    }

    @Test
    fun detectsRomanHindiHinglish() {
        assertEquals("hi", ReplyLanguageDetector.detect("Mujhe batao, abhi kya hua hai?").language)
    }

    @Test
    fun keepsEnglishOnEnglishReplies() {
        assertEquals("en", ReplyLanguageDetector.detect("The update is ready.").language)
    }

    @Test
    fun defaultsEmptyTextToHindiLocale() {
        assertEquals("hi", ReplyLanguageDetector.detect("").language)
    }
}
