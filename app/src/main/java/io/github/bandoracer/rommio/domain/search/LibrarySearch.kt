package io.github.bandoracer.rommio.domain.search

import java.text.Normalizer
import java.util.Locale

/**
 * Library search over the cached catalogue (works offline). Results are ordered by match
 * quality first, then most recently played, then name.
 */
object LibrarySearch {
    enum class Match(val rank: Int) { EXACT(0), PREFIX(1), WORD_PREFIX(2), CONTAINS(3), ALL_WORDS(4) }

    data class Candidate<T>(val item: T, val name: String, val secondary: String = "")
    data class Result<T>(val item: T, val match: Match, val lastPlayedAtEpochMs: Long?)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    fun match(query: String, name: String): Match? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val n = normalize(name)
        if (n.isEmpty()) return null
        val words = n.split(' ')
        return when {
            n == q -> Match.EXACT
            n.startsWith(q) -> Match.PREFIX
            words.any { it.startsWith(q) } -> Match.WORD_PREFIX
            n.contains(q) -> Match.CONTAINS
            q.split(' ').all { term -> words.any { it.startsWith(term) } } -> Match.ALL_WORDS
            else -> null
        }
    }

    fun <T> rank(
        query: String,
        candidates: List<Candidate<T>>,
        lastPlayed: (T) -> Long?,
        limit: Int = 50,
    ): List<Result<T>> = candidates
        .mapNotNull { candidate ->
            val nameMatch = match(query, candidate.name)
            val secondaryMatch = candidate.secondary.takeIf { it.isNotBlank() }?.let { match(query, it) }
            // A match on the secondary text (e.g. platform) ranks after any name match.
            val best = nameMatch ?: secondaryMatch?.let { Match.ALL_WORDS }
            best?.let { Result(candidate.item, it, lastPlayed(candidate.item)) to candidate.name }
        }
        .sortedWith(
            compareBy<Pair<Result<T>, String>> { it.first.match.rank }
                .thenByDescending { it.first.lastPlayedAtEpochMs ?: Long.MIN_VALUE }
                .thenBy { normalize(it.second) },
        )
        .take(limit)
        .map { it.first }
}
