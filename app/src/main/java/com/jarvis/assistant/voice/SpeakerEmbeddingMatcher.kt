package com.jarvis.assistant.voice

import kotlin.math.sqrt

/**
 * Pure, on-device speaker-vector storage and comparison.
 * The legacy single-vector format remains readable for existing enrollments.
 */
internal object SpeakerEmbeddingMatcher {
    fun encode(vectors: List<FloatArray>, threshold: Float): String? {
        if (vectors.size < 3) return null
        val normalized = vectors.map { normalize(it) ?: return null }
        val dimension = normalized.first().size
        if (dimension !in 32..4096 || normalized.any { it.size != dimension }) return null
        for (i in normalized.indices) {
            for (j in i + 1 until normalized.size) {
                if (similarity(normalized[i], normalized[j]) < threshold) return null
            }
        }
        return normalized.joinToString(";") { vector -> vector.joinToString(",") }
    }

    fun decode(stored: String): List<FloatArray>? {
        if (stored.isBlank()) return null
        val encodedVectors = stored.split(";")
        if (encodedVectors.size !in 1..10) return null
        val vectors = encodedVectors.map { encoded ->
            val parts = encoded.split(",")
            if (parts.size !in 32..4096) return null
            val parsed = parts.map { it.toFloatOrNull() ?: return null }.toFloatArray()
            normalize(parsed) ?: return null
        }
        val dimension = vectors.first().size
        if (vectors.any { it.size != dimension }) return null
        return vectors
    }

    fun matches(enrolled: List<FloatArray>, candidate: FloatArray, threshold: Float): Boolean {
        if (enrolled.isEmpty()) return false
        val normalizedCandidate = normalize(candidate) ?: return false
        if (enrolled.any { it.size != normalizedCandidate.size }) return false
        val agreeing = enrolled.count { similarity(it, normalizedCandidate) >= threshold }
        // Legacy enrollments contain one vector; new enrollments require a majority.
        val required = if (enrolled.size == 1) 1 else enrolled.size / 2 + 1
        return agreeing >= required
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(-1f, 1f)
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it.toDouble() }
        if (sum <= 1e-12) return null
        val norm = sqrt(sum).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
