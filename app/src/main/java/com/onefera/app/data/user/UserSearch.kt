package com.onefera.app.data.user

/**
 * Search terms stored on `users/{uid}.searchKeywords`: prefixes of the handle, the whole name
 * and every word of the name, so an `array-contains` query finds "smith" in "John Smith" and
 * "vin" in "@vineel.chalam". The Cloud Function `searchKeywordsFor` builds the same list for
 * profiles written by older builds; keep the two in step.
 */
object UserSearch {
    const val MAX_PREFIX = 20
    const val MAX_KEYWORDS = 300

    fun keywordsFor(displayName: String, username: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(word: String) {
            val w = word.trim().lowercase()
            for (i in 1..minOf(w.length, MAX_PREFIX)) out += w.substring(0, i)
        }
        add(username)
        add(displayName)
        displayName.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { add(it) }
        return out.take(MAX_KEYWORDS)
    }

    /** The term to look up for what the user typed: lower-case, without '@', capped like the stored prefixes. */
    fun termFor(query: String): String = query.trim().removePrefix("@").lowercase().take(MAX_PREFIX)
}
