package com.cleantv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesGrouperTest {

    private fun src(id: Int, title: String, height: Int? = null, ext: String = "mkv"): EpisodeSource {
        val (s, e) = SeriesGrouper.seasonEpisode(0, 0, title)
        return EpisodeSource(id, ext, title, s, e, height, null, id)
    }

    @Test
    fun mergesDuplicateEpisodes() {
        // Real case: every episode listed twice with identical names.
        val eps = SeriesGrouper.mergeEpisodes(
            listOf(
                src(306509, "Marvel's Agents of S.H.I.E.L.D. S01 E01"),
                src(677935, "Marvel's Agents of S.H.I.E.L.D. S01 E01"),
                src(306514, "Marvel's Agents of S.H.I.E.L.D. S01 E02"),
                src(677915, "Marvel's Agents of S.H.I.E.L.D. S01 E02"),
                src(1, "Marvel's Agents of S.H.I.E.L.D. S02 E01"),
            ),
            "Marvel's Agents of S.H.I.E.L.D.",
        )
        assertEquals(3, eps.size)
        assertEquals(2, eps[0].sources.size)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.episode })
    }

    @Test
    fun picksCopyClosestToPreferredQuality() {
        val ep = SeriesGrouper.mergeEpisodes(
            listOf(src(1, "Show S01E01", 2160), src(2, "Show S01E01", 720), src(3, "Show S01E01", 1080)),
            "Show",
        ).single()
        assertEquals(720, ep.sources[SeriesGrouper.playOrder(ep, Quality.HD).first()].height)
        assertEquals(2160, ep.sources[SeriesGrouper.playOrder(ep, Quality.UHD).first()].height)
    }

    @Test
    fun cleansEpisodeTitles() {
        assertEquals("Pilot", SeriesGrouper.episodeTitle("Marvel's Agents of S.H.I.E.L.D. S01 E01 - Pilot", "Marvel's Agents of S.H.I.E.L.D."))
        assertEquals("", SeriesGrouper.episodeTitle("Show S01 E01", "Show"))
    }

    @Test
    fun mergesSameShowButKeepsDifferentShowsApart() {
        val series = listOf(
            SeriesItem(1, "Breaking Bad", "a", null, 0),
            SeriesItem(2, "Breaking Bad 4K [MULTI-SUB]", "b", null, 1),
            SeriesItem(3, "Doctor Who (1963)", "a", null, 2),
            SeriesItem(4, "Doctor Who (2005)", "a", null, 3),
            SeriesItem(5, "Intelligence", "a", null, 4),
            SeriesItem(6, "Intelligence [UK]", "a", null, 5),
            SeriesItem(7, "Berlin", "a", null, 6, tmdb = "111"),
            SeriesItem(8, "Berlin", "b", null, 7, tmdb = "222"),
        )
        val lib = SeriesGrouper.group(listOf(Category("a", "Drama"), Category("b", "EN | Drama")), series, minPrefixCount = 1)
        val names = lib.shows.map { it.name to it.seriesIds.size }
        assertEquals(
            listOf("Breaking Bad" to 2, "Doctor Who (1963)" to 1, "Doctor Who (2005)" to 1,
                "Intelligence" to 1, "Intelligence [UK]" to 1, "Berlin" to 1, "Berlin" to 1),
            names,
        )
    }
}
