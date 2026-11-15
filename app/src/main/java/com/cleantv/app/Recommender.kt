package com.cleantv.app

import kotlin.math.max
import kotlin.math.pow

/**
 * Picks titles for you from what you've watched (pure Kotlin, unit tested).
 *
 * Each watched title adds weight to its genres and provider categories. Recent and finished
 * titles count for more (weight halves every 30 days). Every title in the library is then
 * scored against those weights.
 */
object Recommender {

    class Profile(
        val genres: Map<String, Double>,
        val categories: Map<String, Double>,
        val watched: Set<String>,
        val movieShare: Double,
    ) {
        val isEmpty: Boolean get() = watched.isEmpty()
    }

    private const val HALF_LIFE_DAYS = 30.0

    fun genreKey(g: String): String = g.lowercase().filter { it.isLetter() }
        .replace("scifi", "sciencefiction").replace("sciencefictionfantasy", "sciencefiction")

    fun profile(history: List<Watched>, now: Long = System.currentTimeMillis()): Profile {
        val genres = HashMap<String, Double>()
        val cats = HashMap<String, Double>()
        var movies = 0.0
        var total = 0.0
        for (w in history) {
            val ageDays = max(0L, now - w.time) / 86_400_000.0
            val recency = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            // Watching most of something says more than a 2-minute sample.
            val engagement = if (w.durationMs > 0) 0.4 + 0.6 * w.fraction else 0.7
            val weight = recency * engagement
            // (No Map.merge: it needs Android 7 and the oldest Fire TV Sticks run Android 5.1.)
            for (g in w.genres) genreKey(g).let { genres[it] = (genres[it] ?: 0.0) + weight }
            for (c in w.categoryKeys) cats[c] = (cats[c] ?: 0.0) + weight
            if (w.kind == Kind.MOVIE) movies += weight
            total += weight
        }
        return Profile(genres, cats, history.map { it.key }.toSet(), if (total > 0) movies / total else 0.5)
    }

    /** How much a title shares the genres and categories you watch (0 = nothing in common). */
    fun affinity(p: Profile, t: Title): Double {
        if (p.isEmpty) return 0.0
        val gTotal = p.genres.values.sum().takeIf { it > 0 } ?: 1.0
        val cTotal = p.categories.values.sum().takeIf { it > 0 } ?: 1.0
        val g = t.genres.sumOf { p.genres[genreKey(it)] ?: 0.0 } / gTotal
        val c = t.categoryKeys.sumOf { p.categories[it] ?: 0.0 } / cTotal
        return g * 1.0 + c * 0.6
    }

    fun score(p: Profile, t: Title): Double {
        val a = affinity(p, t)
        if (a <= 0.0) return 0.0
        val kind = if (t.kind == Kind.MOVIE) p.movieShare else 1 - p.movieShare
        val rating = ((t.rating ?: 6.0) - 6.0) / 4.0
        return a + kind * 0.25 + rating * 0.15
    }

    /** Netflix-style "% match", 55-99, relative to the best pick. */
    fun matchPercent(score: Double, best: Double): Int =
        if (best <= 0 || score <= 0) 0 else (55 + 44 * (score / best)).toInt().coerceIn(55, 99)

    /** Highest-scoring titles you haven't watched. */
    fun topPicks(p: Profile, titles: List<Title>, n: Int = 30): List<Pair<Title, Double>> {
        if (p.isEmpty) return emptyList()
        return titles.asSequence()
            .filter { it.key !in p.watched }
            .map { it to score(p, it) }
            .filter { it.second > 0.05 }
            .sortedByDescending { it.second }
            .take(n)
            .toList()
    }

    /** Titles like one you watched: shared genres first, then shared categories, then rating. */
    fun similar(seedGenres: List<String>, seedCats: Set<String>, seedKey: String, titles: List<Title>, exclude: Set<String>, n: Int = 20): List<Title> {
        val sg = seedGenres.map { genreKey(it) }.toSet()
        return titles.asSequence()
            .filter { it.key != seedKey && it.key !in exclude }
            .map { t ->
                val tg = t.genres.map { genreKey(it) }.toSet()
                val union = (sg + tg).size
                val jaccard = if (union == 0) 0.0 else (sg intersect tg).size.toDouble() / union
                val cat = if (t.categoryKeys.any { it in seedCats }) 0.35 else 0.0
                t to (jaccard + cat + ((t.rating ?: 6.0) - 6.0) / 40.0)
            }
            .filter { it.second > 0.3 }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
            .toList()
    }
}
