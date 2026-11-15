package com.cleantv.app

import org.json.JSONObject
import java.net.URLEncoder

/** Critic and audience scores (from OMDb, which carries Rotten Tomatoes, Metacritic and IMDb). */
data class Scores(
    val rottenTomatoes: String? = null,
    val metacritic: String? = null,
    val imdb: String? = null,
    val rated: String? = null,
    val runtime: String? = null,
    val director: String? = null,
    val actors: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val awards: String? = null,
) {
    val isEmpty: Boolean get() = rottenTomatoes == null && metacritic == null && imdb == null
}

/** A published critic's review. */
data class Review(
    val outlet: String,
    val critic: String?,
    val headline: String,
    val summary: String?,
    val stars: Int?,
    val date: String?,
)

data class TmdbHit(
    val id: String,
    val kind: Kind,
    val title: String,
    val year: String?,
    val backdrop: String?,
    val overview: String?,
    val genres: List<String>,
)

/**
 * Looks up extra detail about a title. Every call blocks, so call from [Bg.run].
 * Answers are cached on disk, so the outside services are asked once per title.
 */
object Meta {
    private val providerCache = HashMap<String, RawMeta?>()
    private val tmdbCache = HashMap<String, TmdbHit?>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- Provider (Xtream) ----------------

    fun provider(prefs: Prefs, t: Title): RawMeta? {
        synchronized(providerCache) { if (providerCache.containsKey(t.key)) return providerCache[t.key] }
        val api = XtreamApi(prefs.server, prefs.username, prefs.password)
        val id = t.ids.firstOrNull() ?: return null
        val meta = runCatching { if (t.kind == Kind.MOVIE) api.vodInfo(id) else api.seriesInfo(id) }.getOrNull()
        synchronized(providerCache) { providerCache[t.key] = meta }
        return meta
    }

    /** Best wide picture for the billboard: provider backdrop, then TMDB. */
    fun backdrop(prefs: Prefs, t: Title): String? =
        t.backdrop ?: provider(prefs, t)?.backdrop ?: tmdb(prefs, t)?.backdrop

    // ---------------- TMDB ----------------

    private val MOVIE_GENRES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
        27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
        10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
    )

    private fun tmdbGet(prefs: Prefs, path: String, params: String = ""): JSONObject? {
        val key = prefs.tmdbKey
        if (key.isBlank()) return null
        val bearer = key.length > 40
        val url = "https://api.themoviedb.org/3$path?language=en-GB" + params +
            if (bearer) "" else "&api_key=" + enc(key)
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val body = Net.get(url, 14 * Net.DAY, headers) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun hit(o: JSONObject, kind: Kind): TmdbHit? {
        val id = o.optInt("id", -1).takeIf { it > 0 } ?: return null
        val title = o.optString(if (kind == Kind.MOVIE) "title" else "name").ifBlank { return null }
        val date = o.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date")
        val backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val ids = o.optJSONArray("genre_ids")
        val genres = ArrayList<String>()
        if (ids != null) for (i in 0 until ids.length()) MOVIE_GENRES[ids.optInt(i)]?.let { genres += it }
        o.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) g.optJSONObject(i)?.optString("name")?.let { genres += it } }
        return TmdbHit(
            id.toString(), kind, title, date.take(4).takeIf { it.length == 4 }, backdrop,
            o.optString("overview").ifBlank { null }, genres.distinct(),
        )
    }

    /** The TMDB entry for a title (by the provider's TMDB id, else by searching its name and year). */
    fun tmdb(prefs: Prefs, t: Title): TmdbHit? {
        if (prefs.tmdbKey.isBlank()) return null
        synchronized(tmdbCache) { if (tmdbCache.containsKey(t.key)) return tmdbCache[t.key] }
        val type = if (t.kind == Kind.MOVIE) "movie" else "tv"
        val result = runCatching {
            t.tmdb?.toIntOrNull()?.let { id -> tmdbGet(prefs, "/$type/$id")?.let { hit(it, t.kind) } }
                ?: run {
                    val yearParam = t.year?.let { if (t.kind == Kind.MOVIE) "&year=$it" else "&first_air_date_year=$it" } ?: ""
                    val res = tmdbGet(prefs, "/search/$type", "&query=" + enc(t.name) + yearParam)?.optJSONArray("results")
                    res?.optJSONObject(0)?.let { hit(it, t.kind) }
                }
        }.getOrNull()
        synchronized(tmdbCache) { tmdbCache[t.key] = result }
        return result
    }

    /** TMDB's "if you liked this" list for a title. */
    fun tmdbRecommendations(prefs: Prefs, kind: Kind, tmdbId: String): List<TmdbHit> {
        val type = if (kind == Kind.MOVIE) "movie" else "tv"
        val arr = tmdbGet(prefs, "/$type/$tmdbId/recommendations")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> hit(o, kind) } }
    }

    // ---------------- OMDb (Rotten Tomatoes, Metacritic, IMDb) ----------------

    fun scores(prefs: Prefs, t: Title): Scores? {
        val key = prefs.omdbKey
        if (key.isBlank()) return null
        val type = if (t.kind == Kind.MOVIE) "movie" else "series"
        fun ask(withYear: Boolean): JSONObject? {
            val url = "https://www.omdbapi.com/?apikey=" + enc(key) + "&type=$type&t=" + enc(t.name) +
                if (withYear && t.year != null) "&y=${t.year}" else ""
            val body = Net.get(url, 7 * Net.DAY) ?: return null
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return o.takeIf { it.optString("Response") == "True" }
        }
        val o = ask(true) ?: (if (t.year != null) ask(false) else null) ?: return null
        fun field(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "N/A" }
        var rt: String? = null
        var mc: String? = null
        var imdb: String? = null
        o.optJSONArray("Ratings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val v = r.optString("Value")
                when (r.optString("Source")) {
                    "Rotten Tomatoes" -> rt = v
                    "Metacritic" -> mc = v.substringBefore('/')
                    "Internet Movie Database" -> imdb = v.substringBefore('/')
                }
            }
        }
        if (mc == null) mc = field("Metascore")
        if (imdb == null) imdb = field("imdbRating")
        return Scores(
            rottenTomatoes = rt, metacritic = mc, imdb = imdb,
            rated = field("Rated"), runtime = field("Runtime"), director = field("Director"),
            actors = field("Actors"), plot = field("Plot"), genre = field("Genre"), awards = field("Awards"),
        )
    }

    // ---------------- The Guardian (critic reviews) ----------------

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&nbsp;", " ").trim()

    fun reviews(prefs: Prefs, t: Title): List<Review> {
        val section = if (t.kind == Kind.MOVIE) "film" else "tv-and-radio"
        val url = "https://content.guardianapis.com/search?section=$section&tag=tone/reviews" +
            "&q=" + enc("\"${t.name}\"") +
            "&show-fields=starRating,trailText,byline,headline&page-size=10&order-by=relevance" +
            "&api-key=" + enc(prefs.guardianKey)
        val body = Net.get(url, 7 * Net.DAY) ?: return emptyList()
        val results = runCatching { JSONObject(body).getJSONObject("response").getJSONArray("results") }.getOrNull()
            ?: return emptyList()
        val want = Catalog.normalise(t.name)
        val out = ArrayList<Review>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val f = r.optJSONObject("fields") ?: continue
            val headline = stripHtml(f.optString("headline").ifBlank { r.optString("webTitle") })
            // The headline of a Guardian review starts with the title: "Oppenheimer review – ...".
            val subject = Catalog.normalise(headline.substringBefore(" review"))
            if (want.isEmpty() || !(subject.contains(want) || want.contains(subject) && subject.length > 3)) continue
            val date = r.optString("webPublicationDate").take(10)
            val y = t.year?.toIntOrNull()
            val pubYear = date.take(4).toIntOrNull()
            if (y != null && pubYear != null && pubYear < y - 1) continue
            out += Review(
                outlet = "The Guardian",
                critic = f.optString("byline").ifBlank { null },
                headline = headline,
                summary = f.optString("trailText").ifBlank { null }?.let { stripHtml(it) },
                stars = f.optString("starRating").toIntOrNull()?.takeIf { it in 0..5 },
                date = date.ifBlank { null },
            )
            if (out.size >= 3) break
        }
        return out
    }
}
