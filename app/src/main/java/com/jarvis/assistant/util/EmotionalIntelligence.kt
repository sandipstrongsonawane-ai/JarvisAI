package com.jarvis.assistant.util

/**
 * Lightweight, transparent emotion cue detection for conversational tone.
 * This is not a mental-health classifier and never stores or diagnoses a mood.
 */
object EmotionalIntelligence {
    enum class Tone { DISTRESSED, FRUSTRATED, POSITIVE, GRATEFUL, NEUTRAL }

    data class Assessment(val tone: Tone, val matchedCue: String? = null)

    fun assess(message: String): Assessment {
        val text = message.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return Assessment(Tone.NEUTRAL)

        fun containsCue(cues: List<String>): String? =
            cues.firstOrNull { cue ->
                Regex("(^|\\s)" + Regex.escape(cue) + "($|\\s)").containsMatchIn(text)
            }

        containsCue(listOf(
            "udaas", "dukhi", "pareshan", "tension", "anxious", "worried",
            "overwhelmed", "lonely", "akela", "akeli", "sad", "crying", "rona",
            "scared", "darr", "afraid"
        ))?.let { return Assessment(Tone.DISTRESSED, it) }

        containsCue(listOf(
            "gussa", "angry", "frustrated", "irritated", "annoyed", "chidh",
            "bakwas", "fed up"
        ))?.let { return Assessment(Tone.FRUSTRATED, it) }

        containsCue(listOf(
            "thank", "thanks", "shukriya", "dhanyavaad", "appreciate", "grateful"
        ))?.let { return Assessment(Tone.GRATEFUL, it) }

        containsCue(listOf(
            "khush", "happy", "great", "awesome", "mast", "excited", "accha", "achha"
        ))?.let { return Assessment(Tone.POSITIVE, it) }

        return Assessment(Tone.NEUTRAL)
    }

    /** A short instruction for the local model; neutral messages add no prompt overhead. */
    fun guidanceFor(message: String): String = when (assess(message).tone) {
        Tone.DISTRESSED ->
            "The user may be feeling low, worried, lonely, or overwhelmed. Acknowledge their words gently, be calm and non-judgmental, ask at most one caring open question, and offer practical help without diagnosing or minimizing. Do not assume the cue tells you the whole story."
        Tone.FRUSTRATED ->
            "The user may be frustrated. Stay calm, do not argue or become defensive, acknowledge the inconvenience briefly, and focus on one useful next step."
        Tone.POSITIVE ->
            "The user sounds positive. Match their energy naturally without overdoing praise; keep the conversation genuine."
        Tone.GRATEFUL ->
            "The user expressed gratitude. Respond warmly and briefly, without making the conversation about yourself."
        Tone.NEUTRAL -> ""
    }
}
