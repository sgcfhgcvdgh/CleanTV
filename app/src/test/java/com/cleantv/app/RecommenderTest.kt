package com.cleantv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    private fun title(key: String, kind: Kind, genres: List<String>, cats: Set<String> = emptySet(), rating: Double? = 7.0) =
        Title(kind, key, key, key, null, null, null, rating, genres, null, null, cats, 0L, listOf(1), 0)

    private val now = 1_000_000_000_000L
    private fun watched(t: Title, daysAgo: Int, fraction: Double = 1.0) = Watched(
        t.key, t.kind, t.name, null, null, t.genres, t.categoryKeys,
        now - daysAgo * 86_400_000L, (fraction * 100_000).toLong(), 100_000,
    )

    @Test
    fun picksTitlesFromGenresYouWatch() {
        val heat = title("m:heat", Kind.MOVIE, listOf("Crime", "Thriller"))
        val sicario = title("m:sicario", Kind.MOVIE, listOf("Crime", "Thriller"))
        val frozen = title("m:frozen", Kind.MOVIE, listOf("Animation", "Family"))
        val p = Recommender.profile(listOf(watched(heat, 1)), now)
        val picks = Recommender.topPicks(p, listOf(heat, sicario, frozen)).map { it.first.key }
        assertEquals("m:sicario", picks.first())
        assertFalse("already watched", "m:heat" in picks)
        assertFalse("no shared taste", "m:frozen" in picks)
    }

    @Test
    fun recentViewingCountsMoreThanOld() {
        val crime = title("m:a", Kind.MOVIE, listOf("Crime"))
        val comedy = title("m:b", Kind.MOVIE, listOf("Comedy"))
        val p = Recommender.profile(listOf(watched(crime, 120), watched(comedy, 1)), now)
        assertTrue(p.genres.getValue("comedy") > p.genres.getValue("crime"))
    }

    @Test
    fun sciFiSpellingsCountAsOneGenre() {
        assertEquals(Recommender.genreKey("Sci-Fi"), Recommender.genreKey("Science Fiction"))
    }

    @Test
    fun similarSharesGenres() {
        val seed = title("s:office", Kind.SHOW, listOf("Comedy"), setOf("uk"))
        val parks = title("s:parks", Kind.SHOW, listOf("Comedy"))
        val wire = title("s:wire", Kind.SHOW, listOf("Crime", "Drama"))
        val list = Recommender.similar(seed.genres, seed.categoryKeys, seed.key, listOf(seed, parks, wire), emptySet())
        assertEquals(listOf("s:parks"), list.map { it.key })
    }

    @Test
    fun matchPercentStaysInRange() {
        assertEquals(99, Recommender.matchPercent(2.0, 2.0))
        assertTrue(Recommender.matchPercent(0.1, 2.0) >= 55)
        assertEquals(0, Recommender.matchPercent(0.0, 2.0))
    }
}
