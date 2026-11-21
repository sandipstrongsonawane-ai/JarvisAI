package com.jarvis.assistant.util

/**
 * Splits spoken action chains in English and Hindi/Hinglish without an LLM.
 * Selects the earliest connector in the utterance, so mixed connectors retain order.
 */
object CommandChainSplitter {
    private val separators = listOf(
        Regex("""\s+and\s+then\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+and\s+also\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+after\s+that\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+baad\s+mein\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s*,\s*then\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+then\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+phir\s+""", RegexOption.IGNORE_CASE),
        Regex("""\s+plus\s+""", RegexOption.IGNORE_CASE),
        // "call X and text Y" / "message A and remind me"
        Regex("""\s+and\s+(?=call\b|dial\b|text\b|message\b|sms\b|whatsapp\b|open\b|set\b|remind\b|turn\b|enable\b|disable\b|search\b|play\b|navigate\b|torch\b|flashlight\b|volume\b|wifi\b|bluetooth\b|mute\b|pause\b|resume\b|screenshot\b|read\b|check\b|find\b|close\b|install\b|uninstall\b|lock\b|unlock\b|alarm\b|timer\b|stop\b|start\b|increase\b|decrease\b)""", RegexOption.IGNORE_CASE),
        Regex("""\s+aur\s+(?=call\b|text\b|message\b|sms\b|kholo\b|set\b|yaad\b|chalao\b|karo\b|search\b|volume\b|awaaz\b|wifi\b|bluetooth\b|torch\b|flashlight\b|alarm\b|timer\b|band\b|chalu\b)""", RegexOption.IGNORE_CASE)
    )

    fun looksLikeChain(speech: String): Boolean = split(speech)?.size?.let { it > 1 } == true

    fun split(speech: String): List<String>? {
        var remaining = speech.trim()
        if (remaining.isBlank()) return null
        val parts = mutableListOf<String>()

        while (remaining.isNotBlank()) {
            var earliest: MatchResult? = null
            for (separator in separators) {
                val candidate = separator.find(remaining) ?: continue
                if (earliest == null || candidate.range.first < earliest!!.range.first) {
                    earliest = candidate
                }
            }
            val match = earliest ?: break
            val before = remaining.substring(0, match.range.first).trim()
            if (before.isBlank()) return null
            parts += before
            remaining = remaining.substring(match.range.last + 1).trim()
        }
        if (remaining.isNotBlank()) parts += remaining
        return parts.takeIf { it.size > 1 && it.none(String::isBlank) }
    }
}
