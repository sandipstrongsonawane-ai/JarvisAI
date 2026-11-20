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
        if (expectedWords.size != 4 || heardWords.isEmpty()) return false

        // Offline ASR can substitute or drop one word. Permit one token edit while
        // preserving order and the remaining three unpredictable challenge words.
        val dp = Array(expectedWords.size + 1) { IntArray(heardWords.size + 1) }
        for (i in 0..expectedWords.size) dp[i][0] = i
        for (j in 0..heardWords.size) dp[0][j] = j
        for (i in 1..expectedWords.size) {
            for (j in 1..heardWords.size) {
                val cost = if (expectedWords[i - 1] == heardWords[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[expectedWords.size][heardWords.size] <= 1
    }
}
