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
        fun tokens(value: String) = value.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val expectedWords = tokens(expected)
        val heardWords = tokens(transcript)
        if (expectedWords.size != 4 || heardWords.size != 4) return false

        // Offline ASR may substitute one English word. Keep word count and order
        // fixed so extra/missing words cannot satisfy the random challenge.
        val mismatches = expectedWords.indices.count { expectedWords[it] != heardWords[it] }
        return mismatches <= 1
    }
}
