package com.jarvis.assistant.executor

/**
 * Fail-closed contact resolution for external actions.
 * Exact names win; fuzzy matches are used only when they resolve to one number.
 */
internal object ContactMatchResolver {
    data class Contact(val displayName: String, val number: String)

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun uniqueNumber(matches: List<Contact>): String? {
        val numbers = matches.map { it.number.trim() }.filter { it.isNotBlank() }.distinct()
        return numbers.singleOrNull()
    }

    fun resolve(query: String, contacts: List<Contact>): String? {
        val q = normalize(query)
        if (q.isBlank()) return null
        val normalized = contacts.filter { it.number.isNotBlank() }.map { it to normalize(it.displayName) }
        val exact = normalized.filter { it.second == q }.map { it.first }
        uniqueNumber(exact)?.let { return it }
        if (exact.isNotEmpty()) return null

        val tokenPrefix = normalized.filter { (contact, _) ->
            contact.displayName.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }.any { normalize(it).startsWith(q) }
        }.map { it.first }
        uniqueNumber(tokenPrefix)?.let { return it }
        if (tokenPrefix.isNotEmpty()) return null

        val contains = normalized.filter { it.second.contains(q) }.map { it.first }
        return uniqueNumber(contains)
    }
}
