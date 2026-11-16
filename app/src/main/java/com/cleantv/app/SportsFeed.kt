package com.cleantv.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Live and upcoming games from ESPN's public scoreboard feed (no key needed). */
object SportsFeed {

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun parseTime(iso: String): Long {
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            runCatching { return utc(p).parse(iso)!!.time }
        }
        return 0L
    }

    private fun day(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(c.time)
    }

    /**
     * Games for one league: the current round, plus today and tomorrow for team sports. Blocks.
     * (ESPN answers a date *range* with an error, so days are asked for one at a time.)
     */
    /** Sports requests run 8 at a time, apart from the app's other background work. */
    val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(8)

    /**
     * [offline] returns what was saved last time without asking ESPN (instant, survives restarts).
     * The plain scoreboard already holds today and the current round; sports played every day
     * (basketball, hockey, baseball) also ask for tomorrow.
     */
    fun games(league: League, extraDays: Boolean = true, offline: Boolean = false): List<Game> {
        val base = BASE + league.path + "/scoreboard?limit=200"
        val daily = league.path.startsWith("basketball/") || league.path.startsWith("hockey/") || league.path.startsWith("baseball/")
        val urls = if (daily && extraDays) listOf(base, "$base&dates=${day(1)}") else listOf(base)
        val events = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (url in urls) {
            val body = Net.get(url, 45_000L, offline = offline) ?: continue
            val arr = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (seen.add(e.optString("id"))) events += e
            }
        }
        val out = ArrayList<Game>()
        for (e in events) {
            val comps = ArrayList<JSONObject>()
            e.optJSONArray("competitions")?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.let { comps += it } }
            e.optJSONArray("groupings")?.let { g ->
                for (j in 0 until g.length()) g.optJSONObject(j)?.optJSONArray("competitions")?.let { a ->
                    for (k in 0 until a.length()) a.optJSONObject(k)?.let { comps += it }
                }
            }
            val headToHead = comps.filter { (it.optJSONArray("competitors")?.length() ?: 0) == 2 }
            if (headToHead.isNotEmpty()) {
                for (c in headToHead) parse(league, e, c)?.let { out += it }
            } else if (comps.isNotEmpty()) {
                // A Grand Prix weekend or golf tournament: one card for the session that's on or next.
                val pick = comps.firstOrNull { state(it, e) == GameState.LIVE }
                    ?: comps.firstOrNull { state(it, e) == GameState.UPCOMING }
                    ?: comps.last()
                parse(league, e, pick)?.let { out += it }
            }
        }
        val now = System.currentTimeMillis()
        return out
            // Tennis lists hundreds of matches: keep what's on and the next 12 hours.
            .filter { it.state != GameState.UPCOMING || league.dateRange || it.startMs - now < 12 * Net.HOUR }
            // Results older than a day are dropped.
            .filter { it.state != GameState.FINISHED || now - it.startMs < 30 * Net.HOUR }
            .sortedWith(compareBy<Game> { it.state.ordinal }.thenBy { it.startMs })
    }

    private fun statusOf(c: JSONObject, e: JSONObject): JSONObject? =
        c.optJSONObject("status") ?: e.optJSONObject("status")

    private fun state(c: JSONObject, e: JSONObject): GameState =
        when (statusOf(c, e)?.optJSONObject("type")?.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINISHED
            else -> GameState.UPCOMING
        }

    private fun parse(league: League, e: JSONObject, c: JSONObject): Game? {
        val competitors = ArrayList<Competitor>()
        val arr = c.optJSONArray("competitors") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val team = o.optJSONObject("team")
            val athlete = o.optJSONObject("athlete")
            val roster = o.optJSONObject("roster") // tennis doubles
            val name = team?.optString("displayName")?.ifBlank { null }
                ?: athlete?.optString("displayName")?.ifBlank { null }
                ?: roster?.optString("displayName")?.ifBlank { null } ?: continue
            val short = team?.optString("shortDisplayName")?.ifBlank { null }
                ?: athlete?.optString("shortName")?.ifBlank { null }
                ?: roster?.optString("shortDisplayName")?.ifBlank { null } ?: name
            val logo = team?.optString("logo")?.ifBlank { null }
                ?: athlete?.optJSONObject("flag")?.optString("href")?.ifBlank { null }
            val score = when (val s = o.opt("score")) {
                is String -> s
                is JSONObject -> s.optString("displayValue")
                is Number -> s.toString()
                else -> null
            }?.ifBlank { null } ?: o.optJSONArray("linescores")?.let { ls ->
                // Tennis: games per set, e.g. "6 3 7".
                (0 until ls.length()).mapNotNull { ls.optJSONObject(it)?.optDouble("value")?.takeIf { v -> !v.isNaN() }?.toInt()?.toString() }
                    .joinToString(" ").ifBlank { null }
            }
            competitors += Competitor(
                name, short, team?.optString("abbreviation")?.ifBlank { null }, logo, score,
                o.optString("homeAway") == "home",
            )
        }
        // A race or tournament session may list no competitors at all: it becomes an event card.
        val st = state(c, e)
        val type = statusOf(c, e)?.optJSONObject("type")
        val start = parseTime(c.optString("date").ifBlank { e.optString("date") })
        val detail = when (st) {
            GameState.LIVE -> type?.optString("shortDetail")?.ifBlank { null } ?: "Live"
            GameState.FINISHED -> type?.optString("shortDetail")?.ifBlank { null } ?: "Finished"
            GameState.UPCOMING -> kickoff(start)
        }
        val broadcasts = LinkedHashSet<String>()
        c.optJSONArray("broadcasts")?.let { b ->
            for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONArray("names")?.let { n ->
                for (j in 0 until n.length()) n.optString(j).takeIf { it.isNotBlank() }?.let { broadcasts += it }
            }
        }
        c.optJSONArray("geoBroadcasts")?.let { b ->
            for (i in 0 until b.length()) b.optJSONObject(i)?.optJSONObject("media")?.optString("shortName")
                ?.takeIf { it.isNotBlank() }?.let { broadcasts += it }
        }
        val session = c.optJSONObject("type")?.optString("abbreviation")?.ifBlank { null }
        val eventName = listOfNotNull(e.optString("shortName").ifBlank { e.optString("name") }, session?.takeIf { competitors.size != 2 })
            .joinToString(" · ")
        return Game(
            id = c.optString("id").ifBlank { e.optString("id") },
            league = league, state = st, detail = detail, startMs = start,
            competitors = competitors.take(if (competitors.size == 2) 2 else 0),
            eventName = eventName.ifBlank { league.name },
            broadcasts = broadcasts.toList(),
        )
    }

    /**
     * Which broadcaster shows what on a day (UTC date "yyyy-MM-dd"), from TheSportsDB's
     * premium TV listings. Kept for an hour. Blocks.
     */
    fun tvListings(key: String, date: String, offline: Boolean = false): List<TvListing> {
        if (key.isBlank()) return emptyList()
        val url = "https://www.thesportsdb.com/api/v1/json/" + java.net.URLEncoder.encode(key, "UTF-8") + "/eventstv.php?d=" + date
        val body = Net.get(url, Net.HOUR, offline = offline) ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("tvevents") }.getOrNull() ?: return emptyList()
        val fmt = utc("yyyy-MM-dd HH:mm:ss")
        val out = ArrayList<TvListing>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = runCatching { fmt.parse(o.optString("strTimeStamp"))!!.time }.getOrNull() ?: continue
            val channel = o.optString("strChannel")
            if (channel.isBlank()) continue
            out += TvListing(o.optString("strEvent"), o.optString("strSport"), channel, o.optString("strCountry"), start)
        }
        return out
    }

    /** UTC dates ("yyyy-MM-dd") covering these games. */
    fun utcDates(games: List<Game>): List<String> {
        val fmt = utc("yyyy-MM-dd")
        return games.filter { it.startMs > 0 }.map { fmt.format(java.util.Date(it.startMs)) }.distinct().sorted().take(4)
    }

    /** "15:00", "Tomorrow 19:45", "Sat 12:30" in the TV's own time zone. */
    fun kickoff(ms: Long): String {
        if (ms <= 0) return "Time TBC"
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val time = SimpleDateFormat("HH:mm", Locale.UK).format(c.time)
        val days = (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)) -
            (now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR))
        return when (days) {
            0 -> "Today $time"
            1 -> "Tomorrow $time"
            else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(c.time) + " $time"
        }
    }
}
