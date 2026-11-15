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

    /** Games for one league: yesterday's late finishes through the next 3 days. Blocks. */
    fun games(league: League): List<Game> {
        val url = BASE + league.path + "/scoreboard?limit=200" +
            if (league.dateRange) "&dates=${day(0)}-${day(3)}" else ""
        val body = Net.get(url, 45_000L) ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
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
        // Tennis lists hundreds of matches: keep what's on and the next 12 hours.
        return out.filter { it.state != GameState.UPCOMING || league.dateRange || it.startMs - now < 12 * Net.HOUR }
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
            val name = team?.optString("displayName")?.ifBlank { null }
                ?: athlete?.optString("displayName")?.ifBlank { null } ?: continue
            val short = team?.optString("shortDisplayName")?.ifBlank { null }
                ?: athlete?.optString("shortName")?.ifBlank { null } ?: name
            val logo = team?.optString("logo")?.ifBlank { null }
                ?: athlete?.optJSONObject("flag")?.optString("href")?.ifBlank { null }
            val score = when (val s = o.opt("score")) {
                is String -> s
                is JSONObject -> s.optString("displayValue")
                is Number -> s.toString()
                else -> null
            }?.ifBlank { null }
            competitors += Competitor(
                name, short, team?.optString("abbreviation")?.ifBlank { null }, logo, score,
                o.optString("homeAway") == "home",
            )
        }
        if (competitors.isEmpty()) return null
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
        val eventName = listOfNotNull(e.optString("shortName").ifBlank { e.optString("name") }, session?.takeIf { competitors.size > 2 })
            .joinToString(" · ")
        return Game(
            id = c.optString("id").ifBlank { e.optString("id") },
            league = league, state = st, detail = detail, startMs = start,
            competitors = competitors.take(if (competitors.size == 2) 2 else 0),
            eventName = eventName.ifBlank { league.name },
            broadcasts = broadcasts.toList(),
        )
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
