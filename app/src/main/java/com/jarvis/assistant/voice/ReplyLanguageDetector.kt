package com.jarvis.assistant.voice

import java.util.Locale

/**
 * Lightweight reply-language routing for Android TTS.
 * It intentionally uses distinctive Marathi/Hindi markers rather than treating
 * every Devanagari or Latin-script sentence as the same language.
 */
internal object ReplyLanguageDetector {
    private val marathiLatin = setOf(
        "aahe", "ahe", "aahes", "aahot", "aahet", "ahet",
        "mala", "tula", "tumhala", "majha", "majhi", "majhe",
        "maza", "mazi", "maze", "aamhi", "amhi", "amcha", "amchi",
        "kasa", "kashi", "kase", "kay", "kaay", "kuthe", "ithe", "tithe",
        "ikde", "tikde", "ata", "aata", "zala", "zalay", "jhala", "jhalay",
        "karuya", "karaycha", "karaychi", "kartoy", "kartey", "nahiye",
        "barobar", "changla", "changli", "chhan", "mast", "hoil", "namaskar"
    )
    private val hindiLatin = setOf(
        "hai", "hain", "hoon", "hu", "kya", "kyun", "kaise", "kaisa",
        "mujhe", "mujhko", "mera", "meri", "mere", "tum", "tumhe", "aap",
        "karna", "karunga", "karo", "batao", "raha", "rahi", "abhi",
        "yahan", "wahan", "hua", "kitna", "kab", "kahan", "theek", "accha", "acha"
    )
    private val marathiScript = setOf(
        "आहे", "आहात", "आहोत", "आहेत", "मला", "तुला", "तुम्हाला", "तुम्ही",
        "माझा", "माझी", "माझे", "आम्ही", "आमचा", "आमची",
        "कुठे", "इथे", "तिथे", "आता", "झालं", "झाला", "झाली",
        "करूया", "करतो", "करते", "होईल", "बरं", "छान",
        "चांगला", "चांगली", "मस्त", "काय", "कसे", "कसा", "कशी",
        "म्हणजे", "नाही", "आणि", "पण", "तुझं"
    )
    private val hindiScript = setOf(
        "है", "हैं", "हूँ", "मुझे", "मुझको", "आप", "तुम", "क्यों",
        "कैसे", "करना", "रहा", "रही", "अभी", "यहाँ", "वहाँ", "क्या", "नहीं"
    )

    fun detect(text: String): Locale {
        val normalized = text.lowercase(Locale.ROOT)
        val tokens = Regex("[\\p{L}]+").findAll(normalized).map { it.value }.toSet()
        val marathiScore = tokens.count { it in marathiLatin || it in marathiScript }
        val hindiScore = tokens.count { it in hindiLatin || it in hindiScript }
        return when {
            marathiScore > hindiScore && marathiScore > 0 -> Locale("mr", "IN")
            hindiScore > 0 -> Locale("hi", "IN")
            text.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            tokens.isNotEmpty() -> Locale("en", "IN")
            else -> Locale("hi", "IN")
        }
    }
}
