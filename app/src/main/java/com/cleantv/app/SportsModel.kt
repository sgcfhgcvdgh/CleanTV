package com.cleantv.app

/**
 * Sports listings and the matching of a game to channels in your list (pure Kotlin, unit tested).
 * Fetching from ESPN's public scoreboard feed is in SportsFeed.kt.
 */

data class League(
    val name: String,
    /** ESPN scoreboard path, e.g. "soccer/eng.1". */
    val path: String,
    /** Channels that usually show this league in the UK and US, matched against your channel names. */
    val hints: List<String> = emptyList(),
    /** Team sports take a date range; tennis, golf and racing don't. */
    val dateRange: Boolean = true,
)

data class Sport(val id: String, val name: String, val leagues: List<League>)

enum class GameState { LIVE, UPCOMING, FINISHED }

data class Competitor(
    val name: String,
    val short: String,
    val abbreviation: String?,
    val logo: String?,
    val score: String?,
    val home: Boolean,
)

data class Game(
    val id: String,
    val league: League,
    val state: GameState,
    /** "67'", "2nd Half", "Q3 4:12", "Sat 15:00", "FT"... */
    val detail: String,
    val startMs: Long,
    val competitors: List<Competitor>,
    /** For events with many competitors (a Grand Prix, a golf round). */
    val eventName: String,
    val broadcasts: List<String>,
) {
    val headToHead: Boolean get() = competitors.size == 2
    val home: Competitor? get() = if (headToHead) competitors.firstOrNull { it.home } ?: competitors[0] else null
    val away: Competitor? get() = if (headToHead) competitors.firstOrNull { it != home } else null
    val title: String get() = if (headToHead) "${home!!.name} v ${away!!.name}" else eventName
}

object SportsCatalog {
    val sports: List<Sport> = listOf(
        Sport("football", "Football", listOf(
            League("Premier League", "soccer/eng.1", listOf("sky sports premier league", "sky sports main event", "tnt sports", "amazon prime", "nbc", "peacock", "usa network")),
            League("Champions League", "soccer/uefa.champions", listOf("tnt sports", "amazon prime", "paramount", "cbs sports")),
            League("Europa League", "soccer/uefa.europa", listOf("tnt sports", "paramount")),
            League("Conference League", "soccer/uefa.europa.conf", listOf("tnt sports", "paramount")),
            League("Championship", "soccer/eng.2", listOf("sky sports football", "sky sports efl", "sky sports main event", "sky sports")),
            League("League One", "soccer/eng.3", listOf("sky sports football", "sky sports", "ifollow")),
            League("League Two", "soccer/eng.4", listOf("sky sports football", "sky sports", "ifollow")),
            League("National League", "soccer/eng.5", listOf("tnt sports", "dazn", "national league")),
            League("FA Cup", "soccer/eng.fa", listOf("bbc one", "itv", "tnt sports", "espn")),
            League("Carabao Cup", "soccer/eng.league_cup", listOf("sky sports football", "sky sports main event")),
            League("Scottish Premiership", "soccer/sco.1", listOf("sky sports", "premier sports")),
            League("LaLiga", "soccer/esp.1", listOf("premier sports", "laliga tv", "espn")),
            League("Serie A", "soccer/ita.1", listOf("tnt sports", "paramount")),
            League("Bundesliga", "soccer/ger.1", listOf("sky sports", "espn")),
            League("Ligue 1", "soccer/fra.1", listOf("ligue 1", "bein")),
            League("Eredivisie", "soccer/ned.1", listOf("viaplay", "espn")),
            League("Liga Portugal", "soccer/por.1", listOf("sport tv", "benfica tv")),
            League("Nations League", "soccer/uefa.nations", listOf("sky sports", "fox")),
            League("Internationals", "soccer/fifa.friendly", listOf("itv", "bbc", "sky sports")),
            League("MLS", "soccer/usa.1", listOf("apple", "mls season pass", "fox")),
        )),
        Sport("nfl", "NFL", listOf(
            League("NFL", "football/nfl", listOf("sky sports nfl", "nfl network", "redzone", "cbs", "fox", "nbc", "espn", "dazn")),
            League("College Football", "football/college-football", listOf("espn", "fox", "cbs", "abc")),
        )),
        Sport("basketball", "Basketball", listOf(
            League("NBA", "basketball/nba", listOf("sky sports", "nba tv", "espn", "tnt", "abc")),
            League("WNBA", "basketball/wnba", listOf("espn", "ion")),
            League("College Basketball", "basketball/mens-college-basketball", listOf("espn", "cbs", "fox")),
        )),
        Sport("hockey", "Ice Hockey", listOf(
            League("NHL", "hockey/nhl", listOf("nhl network", "espn", "tnt", "sportsnet", "premier sports")),
        )),
        Sport("baseball", "Baseball", listOf(
            League("MLB", "baseball/mlb", listOf("mlb network", "tnt sports", "fox", "espn")),
        )),
        Sport("rugby", "Rugby", listOf(
            League("Internationals", "rugby/289234", listOf("sky sports", "tnt sports", "bbc", "itv")),
            League("Premiership", "rugby/267979", listOf("tnt sports")),
            League("Six Nations", "rugby/180659", listOf("bbc", "itv")),
            League("URC", "rugby/270557", listOf("premier sports", "s4c")),
            League("Champions Cup", "rugby/271937", listOf("premier sports", "channel 4")),
        )),
        Sport("f1", "Formula 1", listOf(
            League("Formula 1", "racing/f1", listOf("sky sports f1", "f1 tv", "espn", "channel 4"), dateRange = false),
        )),
        Sport("ufc", "UFC / MMA", listOf(
            League("UFC", "mma/ufc", listOf("tnt sports", "espn", "ufc", "paramount"), dateRange = false),
        )),
        Sport("golf", "Golf", listOf(
            League("PGA Tour", "golf/pga", listOf("sky sports golf", "golf channel"), dateRange = false),
            League("DP World Tour", "golf/eur", listOf("sky sports golf", "golf channel"), dateRange = false),
        )),
        Sport("tennis", "Tennis", listOf(
            League("ATP", "tennis/atp", listOf("sky sports tennis", "tennis channel", "amazon prime"), dateRange = false),
            League("WTA", "tennis/wta", listOf("sky sports tennis", "tennis channel"), dateRange = false),
        )),
    )

    /** Every sport at once: the main leagues of each. */
    val all: Sport = Sport("all", "All sports", sports.flatMap { s -> s.leagues.take(if (s.id == "football") 6 else 2) })

    /** The Sports screen's tabs: All sports first. */
    val tabs: List<Sport> = listOf(all) + sports

    fun sport(id: String): Sport = tabs.firstOrNull { it.id == id } ?: all
}

/** Finds the channels in your list that are likely showing a game. */
object GameMatcher {

    data class Candidate(val key: String, val name: String, val score: Int, val reason: String)

    /** Names too common to identify a team on their own. */
    private val GENERIC = setOf(
        "united", "city", "fc", "afc", "cf", "sc", "real", "club", "athletic", "athletico", "sporting",
        "county", "town", "rovers", "wanderers", "albion", "the", "de", "of", "and", "new", "west", "north",
        "south", "east", "saint", "st", "inter", "hotspur", "manchester", "sheffield", "bristol", "state",
        "university", "college", "team", "women", "men", "national", "rangers", "celtic", "athletics",
    )

    /** Names fans and channel lists use instead of the official one. */
    private val NICKNAMES = mapOf(
        "manchester united" to listOf("man utd", "man united"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("spurs", "tottenham"),
        "wolverhampton wanderers" to listOf("wolves"),
        "brighton & hove albion" to listOf("brighton"),
        "brighton and hove albion" to listOf("brighton"),
        "west ham united" to listOf("west ham"),
        "newcastle united" to listOf("newcastle"),
        "nottingham forest" to listOf("nottm forest", "forest"),
        "afc bournemouth" to listOf("bournemouth"),
        "paris saint-germain" to listOf("psg", "paris sg"),
        "internazionale" to listOf("inter milan", "inter"),
        "bayern munich" to listOf("bayern"),
        "atletico madrid" to listOf("atletico", "atleti"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "sheffield wednesday" to listOf("sheff wed"),
        "sheffield united" to listOf("sheff utd"),
        "queens park rangers" to listOf("qpr"),
        "west bromwich albion" to listOf("west brom"),
    )

    /** Channel names normalised once, not once per game (thousands of channels x dozens of games). */
    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun normCached(s: String): String = normCache[s] ?: norm(s).also { if (normCache.size < 100_000) normCache[s] = it }

    fun norm(s: String): String =
        s.lowercase().replace('&', ' ').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    fun aliases(c: Competitor): List<String> {
        val out = LinkedHashSet<String>()
        val full = norm(c.name)
        out += full
        out += norm(c.short)
        NICKNAMES[c.name.lowercase()]?.forEach { out += norm(it) }
        // "Arsenal FC" -> "arsenal"; "AFC Bournemouth" -> "bournemouth"
        val words = full.split(' ').filter { it !in setOf("fc", "afc", "cf", "sc") }
        if (words.isNotEmpty()) out += words.joinToString(" ")
        words.firstOrNull()?.takeIf { it.length >= 5 && it !in GENERIC }?.let { out += it }
        words.lastOrNull()?.takeIf { words.size > 1 && it.length >= 5 && it !in GENERIC }?.let { out += it }
        return out.filter { it.length >= 3 && it !in GENERIC }
    }

    private fun containsWords(haystack: String, needle: String): Boolean =
        needle.isNotEmpty() && " $haystack ".contains(" $needle ")

    /**
     * Ranks channels for a game. [channels] are (key, name) pairs from the channel list;
     * [guide] is what's on each channel (by key) from the TV guide, when known.
     *
     * 150+  the TV guide says this channel is showing it
     * 100+  an event channel named after both teams, dated for this game (or undated)
     * below  channels that mention one team, the broadcaster, or usually carry the league
     *
     * Event channels dated for another day, and "No Scheduled Event" / "Postponed" slots, are skipped.
     */
    fun find(
        game: Game,
        channels: List<Pair<String, String>>,
        guide: Map<String, List<Programme>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): List<Candidate> {
        val teamAliases = game.competitors.takeIf { game.headToHead }?.map { c ->
            // An alias both teams share ("manchester") can't tell them apart.
            c to aliases(c)
        } ?: emptyList()
        val shared = if (teamAliases.size == 2) teamAliases[0].second.intersect(teamAliases[1].second.toSet()) else emptySet()
        val broadcasts = game.broadcasts.map { norm(it) }.filter { it.length >= 2 }
        val hints = game.league.hints.map { norm(it) }
        val leagueWords = norm(game.league.name)
        val eventWords = norm(game.eventName).split(' ').filter { it.length >= 5 && it !in GENERIC }
        // The moment that matters: kick-off, or now if it's already on.
        val at = if (game.state == GameState.LIVE || game.startMs <= 0) now else game.startMs

        fun teamsIn(text: String) = teamAliases.filter { (_, al) -> al.any { it !in shared && containsWords(text, it) } }

        val out = ArrayList<Candidate>()
        for ((key, rawName) in channels) {
            if (EventNames.isEmptySlot(rawName)) continue
            // An event channel named for a different day is out of date: ignore its name.
            val dated = EventNames.dateIn(rawName)
            val staleName = dated != null && game.startMs > 0 && !EventNames.sameDay(dated, game.startMs)
            val name = normCached(rawName)
            var score = 0
            val reasons = ArrayList<String>()

            // 1. The TV guide.
            val prog = guide[key]?.firstOrNull { it.start <= at + 20 * 60_000L && it.end > at }
            if (prog != null) {
                val text = norm(prog.title + " " + (prog.description ?: ""))
                if (game.headToHead) {
                    when (teamsIn(text).size) {
                        2 -> { score += 150; reasons += "Guide: ${prog.title}" }
                        1 -> if (containsWords(text, leagueWords) || teamsIn(name).isNotEmpty()) { score += 60; reasons += "Guide: ${prog.title}" }
                    }
                } else if (containsWords(text, leagueWords) || eventWords.any { containsWords(text, it) }) {
                    score += 130; reasons += "Guide: ${prog.title}"
                }
            }

            // 2. The channel's name.
            if (!staleName) {
                val hitTeams = teamsIn(name)
                when (hitTeams.size) {
                    2 -> { score += 100; reasons += "Shows this game" }
                    1 -> { score += 40; reasons += "Mentions ${hitTeams[0].first.short.ifBlank { hitTeams[0].first.name }}" }
                }
                if (!game.headToHead && dated != null && eventWords.any { containsWords(name, it) }) {
                    score += 100; reasons += "Shows this event"
                }
            }
            broadcasts.firstOrNull { containsWords(name, it) }?.let { score += 30; reasons += "Broadcaster: ${it.uppercase()}" }
            if (score < 100 && dated == null) hints.firstOrNull { containsWords(name, it) }?.let {
                score += 20
                if (reasons.isEmpty()) reasons += "Usually shows ${game.league.name}"
            }
            if (!game.headToHead && score > 0 && containsWords(name, leagueWords)) score += 10
            if (score >= 20) out += Candidate(key, rawName, score, reasons.joinToString(" · "))
        }
        return out.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name.length })
    }
}

/** Reading provider event-channel names: the date in them, and empty slots. */
object EventNames {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    // Whole month names only, so "Marseille 3" isn't read as March 3.
    private const val MON = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?(?![a-z])"""
    // "Sat 14 Nov", "13 May"
    private val DAY_MONTH = Regex("""(?i)(?<![\d:])(\d{1,2})\s+$MON""")
    // "@ Nov 15 1:00 PM", "Oct 25" (but not the "3" of "Nov 3:00pm")
    private val MONTH_DAY = Regex("""(?i)(?<![a-z])$MON\s+(\d{1,2})(?![:\d])""")
    // "14/11"
    private val SLASH = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])""")
    private val EMPTY = Regex("""(?i)no\s+scheduled\s+event|postponed|cancell?ed""")

    fun isEmptySlot(name: String): Boolean = EMPTY.containsMatchIn(name)

    /** (month 1-12, day) written in a channel name, or null. */
    fun dateIn(name: String): Pair<Int, Int>? {
        DAY_MONTH.find(name)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            if (d in 1..31 && mo > 0) return mo to d
        }
        MONTH_DAY.find(name)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[1].lowercase().take(3)) + 1
            val d = m.groupValues[2].toInt()
            if (d in 1..31 && mo > 0) return mo to d
        }
        SLASH.find(name)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            if (d in 1..31 && mo in 1..12) return mo to d
        }
        return null
    }

    /** Whether a (month, day) is the game's day, allowing a day either side for time zones. */
    fun sameDay(date: Pair<Int, Int>, startMs: Long): Boolean {
        val game = java.util.Calendar.getInstance().apply { timeInMillis = startMs }
        for (yearShift in -1..1) {
            val c = java.util.Calendar.getInstance().apply {
                clear()
                set(game.get(java.util.Calendar.YEAR) + yearShift, date.first - 1, date.second, 12, 0)
            }
            if (kotlin.math.abs(c.timeInMillis - startMs) <= 36 * 3_600_000L) return true
        }
        return false
    }
}
