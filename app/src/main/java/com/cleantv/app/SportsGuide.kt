package com.cleantv.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Finds where each game is on, using every channel you have (hidden Live TV categories
 * included): the TV guide of your sports channels, then event-channel names dated for
 * the game, then broadcasters and usual channels.
 */
object SportsGuide {

    /** Best channels for each game id, from the last look-up. */
    val matches = ConcurrentHashMap<String, List<GameMatcher.Candidate>>()

    private val SPORTY = Regex("(?i)sport|football|soccer|rugby|tennis|golf|f1|formula|nba|nfl|nhl|mlb|ufc|boxing|cricket|racing|tnt|dazn|espn|premier|laliga|bein|eurosport|euro sport|fox|nbc|cbs|abc|bbc|itv|channel 4|amazon|prime|peacock|paramount")
    private val EVENTISH = Regex("""(?i)event|ppv|friendly|ifollow|national league|max sports|teams|\+""")

    /**
     * Channels whose TV guide is worth reading: sports channels that aren't event slots,
     * those that usually carry the leagues being looked up first.
     */
    private fun guideChannels(lib: GroupedLibrary, games: List<Game>): List<ChannelGroup> {
        val catNames = lib.categories.associate { it.key to it.name }
        val hints = games.flatMap { it.league.hints }.map { GameMatcher.norm(it) }.distinct()
        return lib.channels.asSequence()
            .filter { ch -> !EventNames.isEmptySlot(ch.name) && EventNames.dateIn(ch.name) == null }
            .filter { ch -> ch.categoryKeys.none { EVENTISH.containsMatchIn(catNames[it] ?: "") } }
            .filter { ch -> SPORTY.containsMatchIn(ch.name) || ch.categoryKeys.any { SPORTY.containsMatchIn(catNames[it] ?: "") } }
            .map { ch -> ch to (if (hints.any { h -> (" " + GameMatcher.norm(ch.name) + " ").contains(" $h") }) 0 else 1) }
            .sortedBy { it.second }
            .map { it.first }
            .take(90)
            .toList()
    }

    /**
     * Looks up every game (blocks; call from a background thread). Refreshes the channel list
     * first when it's more than 30 minutes old, since event channels are renamed through the day.
     */
    fun matchAll(prefs: Prefs, games: List<Game>): Map<String, List<GameMatcher.Candidate>> {
        if (games.isEmpty()) return emptyMap()
        val api = XtreamApi(prefs.server, prefs.username, prefs.password)
        val stale = System.currentTimeMillis() - Library.loadedAt > 30 * Net.MINUTE
        val lib = runCatching { Library.load(api, force = stale && Library.data != null) }.getOrNull()
            ?: Library.data ?: return emptyMap()
        val pairs = lib.channels.map { it.key to it.name }
        val now = System.currentTimeMillis()
        // Only games on now or within six hours can be checked against the guide.
        val soon = games.filter { it.state == GameState.LIVE || (it.state == GameState.UPCOMING && it.startMs - now < 6 * Net.HOUR) }
        val guide = if (soon.isEmpty()) emptyMap() else Epg.loadAll(prefs, guideChannels(lib, soon))
        // Broadcaster listings (TheSportsDB premium key), for every day these games are on.
        val active = games.filter { it.state != GameState.FINISHED }
        val listings = if (prefs.sportsDbKey.isBlank()) emptyList()
        else SportsFeed.utcDates(active).flatMap { SportsFeed.tvListings(prefs.sportsDbKey, it) }
        val out = HashMap<String, List<GameMatcher.Candidate>>()
        for (g in games) {
            if (g.state == GameState.FINISHED) continue
            val found = GameMatcher.find(g, pairs, guide, now, listings)
            out[g.id] = found
            matches[g.id] = found
        }
        return out
    }

    /**
     * A look-up using only what's already on the TV (no downloads): the loaded channel list,
     * guides fetched earlier and saved TV listings. Takes a moment, not seconds.
     */
    fun quickMatch(prefs: Prefs, game: Game): List<GameMatcher.Candidate>? {
        val lib = Library.data ?: return null
        val guide = Epg.cachedOnly(guideChannels(lib, listOf(game)))
        val listings = if (prefs.sportsDbKey.isBlank()) emptyList()
        else SportsFeed.utcDates(listOf(game)).flatMap { SportsFeed.tvListings(prefs.sportsDbKey, it, offline = true) }
        return GameMatcher.find(game, lib.channels.map { it.key to it.name }, guide, System.currentTimeMillis(), listings)
    }

    /** Whether a game is confidently on one of your channels. */
    fun watchable(found: List<GameMatcher.Candidate>?): Boolean = (found?.firstOrNull()?.score ?: 0) >= 100
}
