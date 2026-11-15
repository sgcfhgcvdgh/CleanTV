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
}
