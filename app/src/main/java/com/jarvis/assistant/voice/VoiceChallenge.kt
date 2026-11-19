package com.jarvis.assistant.voice

import java.security.SecureRandom

/** Fresh one-use phrase used to make stale, pre-recorded voice samples less useful. */
object VoiceChallenge {
    private val random = SecureRandom()
    private val words = listOf(
        "amber", "birch", "copper", "delta", "falcon", "garden", "harbor", "indigo",
        "jungle", "kitten", "lemon", "magnet", "nectar", "ocean", "pepper", "quartz",
        "rocket", "silver", "tiger", "velvet", "willow", "yellow", "zephyr"
    )

    @Synchronized
    fun create(): String = (1..4).map { words[random.nextInt(words.size)] }.joinToString(" ")

    fun matches(expected: String, transcript: String): Boolean {
        fun normalize(value: String) = value.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return expected.isNotBlank() && normalize(expected) == normalize(transcript)
    }
}
