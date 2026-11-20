package com.jarvis.assistant.voice

import java.util.Locale

/** Lightweight language routing for Android TTS. */
internal object ReplyLanguageDetector {
    private val indiaHinglishLatin = setOf(
        "aahe", "ahe", "aahes", "aahot", "aahet", "ahet", "mala", "tula",
        "tumhala", "majha", "majhi", "majhe", "maza", "mazi", "maze",
        "aamhi", "amhi", "amcha", "amchi", "kasa", "kashi", "kase", "kay",
        "kaay", "kuthe", "ithe", "tithe", "ikde", "tikde", "ata", "aata",
        "zala", "zalay", "jhala", "jhalay", "karuya", "karaycha", "karaychi",
        "kartoy", "kartey", "nahiye", "barobar", "changla", "changli",
        "chhan", "mast", "hoil", "namaskar",
        "hai", "hain", "hoon", "hu", "mujhe", "mujhko", "kya", "kyun",
        "kaise", "kaisa", "mera", "meri", "mere", "tum", "tumhe", "aap",
        "karna", "karunga", "karo", "batao", "raha", "rahi", "abhi",
        "yahan", "wahan", "hua", "kitna", "kab", "kahan", "theek", "accha", "acha"
    )


    private val hindiScript = setOf(
        "है", "हैं", "हूँ", "मुझे", "मुझको", "आप", "तुम", "क्यों",
        "कैसे", "करना", "रहा", "रही", "अभी", "यहाँ", "वहाँ", "क्या", "नहीं"
    )

    fun detect(text: String): Locale {
        val normalized = text.lowercase(Locale.ROOT)
        val tokens = Regex("[\\p{L}]+").findAll(normalized).map { it.value }.toSet()
        val hinglishScore = tokens.count { it in indiaHinglishLatin }

        // Roman Marathi/Maharashtra vocabulary is treated as Hindi-locale Hinglish.
        // Devanagari replies use Hindi TTS; no Marathi voice is selected.
        val hindiScriptScore = hindiScript.count { normalized.contains(it) }
        val hindiScore = hinglishScore + (hindiScriptScore * 2)

        return when {
            hindiScore > 0 -> Locale("hi", "IN")
            text.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            tokens.isNotEmpty() -> Locale("en", "IN")
            else -> Locale("hi", "IN")
        }
    }
}
