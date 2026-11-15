package com.cleantv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMatcherTest {

    private val epl = SportsCatalog.sport("football").leagues.first()

    private fun game(home: String, homeShort: String, away: String, awayShort: String, broadcasts: List<String> = emptyList()) = Game(
        "1", epl, GameState.LIVE, "67'", 0L,
        listOf(
            Competitor(home, homeShort, null, null, "1", true),
            Competitor(away, awayShort, null, null, "0", false),
        ),
        "", broadcasts,
    )

    private val channels = listOf(
        "a" to "UK: Sky Sports Premier League FHD",
        "b" to "EPL 03 | Arsenal vs Chelsea 17:30",
        "c" to "EPL 04 | Man Utd vs Man City 20:00",
        "d" to "UK: BBC One HD",
        "e" to "Arsenal TV",
        "f" to "US: NBC HD",
    )

    @Test
    fun eventChannelWithBothTeamsWins() {
        val found = GameMatcher.find(game("Arsenal", "Arsenal", "Chelsea", "Chelsea"), channels)
        assertEquals("b", found.first().key)
        assertTrue(found.first().score >= 100)
    }

    @Test
    fun manchesterDerbyUsesNicknamesNotTheSharedCityName() {
        val found = GameMatcher.find(game("Manchester United", "Man United", "Manchester City", "Man City"), channels)
        assertEquals("c", found.first().key)
        assertTrue(found.none { it.key == "b" })
    }

    @Test
    fun leagueChannelsAndBroadcastersAreOffered() {
        val found = GameMatcher.find(game("Fulham", "Fulham", "Brentford", "Brentford", listOf("NBC")), channels)
        val keys = found.map { it.key }
        assertTrue("a" in keys)
        assertTrue("f" in keys)
        assertTrue("d" !in keys)
    }

    @Test
    fun aliasesDropClubSuffixes() {
        val al = GameMatcher.aliases(Competitor("AFC Bournemouth", "Bournemouth", "BOU", null, null, true))
        assertTrue("bournemouth" in al)
    }

    // ---- Real names from the provider's list ----

    private fun at(month: Int, day: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
        clear(); set(2026, month - 1, day, hour, 0)
    }.timeInMillis

    private fun gameOn(start: Long, home: String, away: String, league: League = epl, state: GameState = GameState.UPCOMING) = Game(
        "x", league, state, "", start,
        listOf(Competitor(home, home, null, null, null, true), Competitor(away, away, null, null, null, false)),
        "", emptyList(),
    )

    @Test
    fun readsDatesInEventNames() {
        assertEquals(11 to 15, EventNames.dateIn("Philadelphia 76ers vs Indiana Pacers @ Nov 15 1:00 PM :NBA 01"))
        assertEquals(5 to 13, EventNames.dateIn("Villarreal vs. Sevilla - UK Wed 13 May 5:55pm - ET Wed 13 May 12:55pm :Amazon UK 01"))
        assertEquals(11 to 14, EventNames.dateIn("Gateshead vs Barrow // UK Sat 14 Nov 3:00pm // ET Sat 14 Nov 10:00am"))
        assertEquals(11 to 14, EventNames.dateIn("EFL02| Bromley v Notts County (L1) 14/11"))
        assertEquals(null, EventNames.dateIn("Marseille 3 Lyon"))
        assertEquals(null, EventNames.dateIn("FRIENDLY 12: Derby County 15:00 Atromitos FC"))
    }

    @Test
    fun outOfDateEventNameIsIgnored() {
        val game = gameOn(at(11, 15, 15), "Crystal Palace", "Chelsea")
        val found = GameMatcher.find(game, listOf("a" to "Crystal Palace v Chelsea @ Oct 25 11:45 AM :Sky Sports UK 04"), now = at(11, 15, 12))
        assertTrue(found.isEmpty())
    }

    @Test
    fun eventNameForTodayIsPlayed() {
        val nba = SportsCatalog.sport("basketball").leagues.first()
        val game = gameOn(at(11, 15, 13), "Philadelphia 76ers", "Indiana Pacers", nba)
        val found = GameMatcher.find(
            game,
            listOf(
                "a" to "Philadelphia 76ers vs Indiana Pacers @ Nov 15 1:00 PM :NBA 01",
                "b" to "Philadelphia 76ers vs Indiana Pacers @ Nov 8 1:00 PM :NBA 07",
            ),
            now = at(11, 15, 12),
        )
        assertEquals(listOf("a"), found.map { it.key })
        assertTrue(found[0].score >= 100)
    }

    @Test
    fun emptySlotsAreSkipped() {
        val game = gameOn(at(11, 14, 15), "Bromley", "Notts County")
        val found = GameMatcher.find(
            game,
            listOf("a" to "EFL02| Bromley POSTPONED Notts County (L1) 14/11", "b" to "Football Event 09: No Scheduled Event"),
            now = at(11, 14, 12),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun tvGuideFindsTheMainChannel() {
        val start = at(11, 15, 16)
        val game = gameOn(start, "Arsenal", "Chelsea", state = GameState.LIVE)
        val guide = mapOf(
            "main" to listOf(Programme("Live Premier League: Arsenal v Chelsea", null, start - 30 * 60_000L, start + 2 * 3_600_000L)),
            "pl" to listOf(Programme("Premier League Review", null, start - 30 * 60_000L, start + 3_600_000L)),
        )
        val found = GameMatcher.find(
            game,
            listOf("main" to "Sky Sports Main Event", "pl" to "Sky Sports Premier League"),
            guide, now = start + 10 * 60_000L,
        )
        assertEquals("main", found.first().key)
        assertTrue(found.first().score >= 150)
    }
}
