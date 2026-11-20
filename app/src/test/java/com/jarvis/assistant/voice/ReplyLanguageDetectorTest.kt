package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyLanguageDetectorTest {
    @Test
    fun routesMarathiDevanagariToHindiTts() {
        assertEquals("hi", ReplyLanguageDetector.detect("मला मदत करा, हे करूया.").language)
        assertEquals("hi", ReplyLanguageDetector.detect("तुम्ही कसे आहात?").language)
    }

    @Test
    fun routesMaharashtraHinglishToHindiTts() {
        assertEquals("hi", ReplyLanguageDetector.detect("Mala sang, ata kay karuya?").language)
        assertEquals("hi", ReplyLanguageDetector.detect("Majha phone kuthe aahe?").language)
    }

    @Test
    fun detectsHindiInDevanagari() {
        assertEquals("hi", ReplyLanguageDetector.detect("मुझे बताओ, यह कैसे करना है?").language)
        assertEquals("hi", ReplyLanguageDetector.detect("आप अभी कहाँ हैं?").language)
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
