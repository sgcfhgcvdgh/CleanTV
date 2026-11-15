package com.cleantv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String) : Exception(message)

/** Optional detail the provider sends about a movie or show. */
data class RawMeta(
    val plot: String? = null,
    val genre: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    val year: String? = null,
    val added: Long = 0,
    val ext: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val duration: String? = null,
    val tmdb: String? = null,
    val age: String? = null,
)

class XtreamApi(serverInput: String, private val username: String, private val password: String) {

    val server: String = normalizeServer(serverInput)

    companion object {
        const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** Accepts "example.com:8080", "http://example.com:8080/" or a full get.php / player_api.php link. */
        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val cut = listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php", "/live/")
                .map { s.indexOf(it, ignoreCase = true) }
                .filter { it > 0 }
                .minOrNull()
            if (cut != null) s = s.substring(0, cut)
            return s.trimEnd('/')
        }

        fun friendlyError(e: Throwable): String = when (e) {
            is ApiException -> e.message ?: "Something went wrong"
            is java.net.UnknownHostException -> "Can't find that server – check the address"
            is java.net.SocketTimeoutException -> "The server took too long to answer"
            is java.net.ConnectException -> "Couldn't connect to the server – check the address and port"
            else -> "Couldn't reach the server (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(action: String?): String = buildString {
        append(server).append("/player_api.php?username=").append(enc(username))
        append("&password=").append(enc(password))
        if (action != null) append("&action=").append(action)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw ApiException("Server refused the login (error $code)")
            if (code !in 200..299) throw ApiException("Server replied with error $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Checks the login. Throws ApiException with a readable message if it fails. */
    fun login() {
        val body = get(apiUrl(null))
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiException("That doesn't look like an Xtream Codes server")
        }
        val info = json.optJSONObject("user_info")
            ?: throw ApiException("Login failed – check the server address")
        if (info.optInt("auth", 0) != 1) throw ApiException("Wrong username or password")
        val status = info.optString("status", "")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw ApiException("Account status: $status")
        }
    }

    private fun getArray(action: String): JSONArray {
        val url = apiUrl(action)
        val body = Net.provider(url, 12 * Net.HOUR) { get(url) }.trim()
        return if (body.startsWith("[")) JSONArray(body) else JSONArray()
    }

    fun categories(): List<Category> {
        val arr = getArray("get_live_categories")
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Category(o.optString("category_id"), o.optString("category_name"))
        }
        return out
    }

    fun streams(): List<Stream> {
        val arr = getArray("get_live_streams")
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            val icon = o.optString("stream_icon", "").takeIf { it.startsWith("http") }
            out += Stream(id, o.optString("name"), o.optString("category_id"), icon, i)
        }
        return out
    }

    // ---------- TV shows ----------

    fun seriesCategories(): List<Category> {
        val arr = getArray("get_series_categories")
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Category(o.optString("category_id"), o.optString("category_name"))
        }
        return out
    }

    fun series(meta: MutableMap<Int, RawMeta>? = null): List<SeriesItem> {
        val arr = getArray("get_series")
        val out = ArrayList<SeriesItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("series_id", -1)
            if (id < 0) continue
            val cover = o.optString("cover", "").takeIf { it.startsWith("http") }
            val tmdb = o.optString("tmdb", "").ifBlank { o.optString("tmdb_id", "") }
            out += SeriesItem(id, o.optString("name"), o.optString("category_id"), cover, i, tmdb)
            meta?.put(id, rawMeta(o))
        }
        return out
    }

    // ---------- Movies ----------

    fun vodCategories(): List<Category> {
        val arr = getArray("get_vod_categories")
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Category(o.optString("category_id"), o.optString("category_name"))
        }
        return out
    }

    /** Movies, shaped like series items so the same duplicate merging can be used. */
    fun vodStreams(meta: MutableMap<Int, RawMeta>): List<SeriesItem> {
        val arr = getArray("get_vod_streams")
        val out = ArrayList<SeriesItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            val icon = o.optString("stream_icon", "").takeIf { it.startsWith("http") }
            val tmdb = o.optString("tmdb", "").ifBlank { o.optString("tmdb_id", "") }
            out += SeriesItem(id, o.optString("name"), o.optString("category_id"), icon, i, tmdb)
            meta[id] = rawMeta(o)
        }
        return out
    }

    /** Extra detail for one movie (plot, backdrop, cast...). */
    fun vodInfo(streamId: Int): RawMeta? {
        val body = get(apiUrl("get_vod_info") + "&vod_id=" + streamId).trim()
        if (!body.startsWith("{")) return null
        val info = JSONObject(body).optJSONObject("info") ?: return null
        return rawMeta(info)
    }

    /** Extra detail for one series. */
    fun seriesInfo(seriesId: Int): RawMeta? {
        val body = get(apiUrl("get_series_info") + "&series_id=" + seriesId).trim()
        if (!body.startsWith("{")) return null
        val info = JSONObject(body).optJSONObject("info") ?: return null
        return rawMeta(info)
    }

    /** What's on a channel now and next (titles come base64-encoded from most panels). */
    fun shortEpg(streamId: Int, limit: Int = 4): List<Programme> {
        val body = get(apiUrl("get_short_epg") + "&stream_id=" + streamId + "&limit=" + limit).trim()
        if (!body.startsWith("{")) return emptyList()
        val arr = JSONObject(body).optJSONArray("epg_listings") ?: return emptyList()
        fun text(v: String): String {
            if (v.isBlank()) return ""
            return runCatching {
                String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotBlank() && it.none { c -> c == '�' } } ?: v
        }
        val out = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val end = (o.optString("stop_timestamp").ifBlank { o.optString("end_timestamp") }).toLongOrNull()?.times(1000) ?: continue
            if (end <= start) continue
            out += Programme(text(o.optString("title")).trim(), text(o.optString("description")).trim().ifBlank { null }, start, end)
        }
        return out.sortedBy { it.start }
    }

    fun movieUrl(id: Int, ext: String): String = "$server/movie/$username/$password/$id.$ext"

    /** All episode files of one series, from get_series_info. Handles the different shapes panels send. */
    fun seriesEpisodes(seriesId: Int): List<EpisodeSource> {
        val body = get(apiUrl("get_series_info") + "&series_id=" + seriesId).trim()
        if (!body.startsWith("{")) return emptyList()
        val json = JSONObject(body)
        val objects = ArrayList<JSONObject>()
        fun collect(a: JSONArray) {
            for (i in 0 until a.length()) {
                val item = a.opt(i)
                if (item is JSONObject) objects += item else if (item is JSONArray) collect(item)
            }
        }
        when (val eps = json.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { collect(it) } }
            is JSONArray -> collect(eps)
        }
        val out = ArrayList<EpisodeSource>(objects.size)
        objects.forEachIndexed { i, o ->
            val id = o.optString("id").toIntOrNull() ?: return@forEachIndexed
            val title = o.optString("title")
            val info = o.optJSONObject("info")
            val height = info?.optJSONObject("video")?.optInt("height", 0)?.takeIf { it > 0 }
            val duration = info?.optInt("duration_secs", 0)?.takeIf { it > 0 }
            val (season, episode) = SeriesGrouper.seasonEpisode(o.optInt("season", 0), o.optInt("episode_num", 0), title)
            val ext = o.optString("container_extension", "").ifBlank { "mp4" }
            out += EpisodeSource(id, ext, title, season, episode, height, duration, seriesId * 10_000 + i)
        }
        return out
    }

    private fun rawMeta(o: JSONObject): RawMeta {
        fun str(vararg keys: String): String? = keys.asSequence()
            .map { o.optString(it, "").trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" && it != "0" }
        fun url(key: String): String? = when (val v = o.opt(key)) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
            is String -> v.takeIf { it.startsWith("http") }
            else -> null
        }
        val rating = str("rating")?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
        val date = str("releaseDate", "release_date", "releasedate", "year")
        val year = date?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        val added = str("added", "last_modified")?.toLongOrNull() ?: 0L
        return RawMeta(
            plot = str("plot", "description"),
            genre = str("genre"),
            backdrop = url("backdrop_path"),
            rating = rating,
            year = year,
            added = added,
            ext = str("container_extension"),
            director = str("director"),
            cast = str("cast", "actors"),
            duration = str("duration", "episode_run_time"),
            tmdb = str("tmdb_id", "tmdb"),
            age = str("age", "mpaa_rating"),
        )
    }

    fun episodeUrl(id: Int, ext: String): String = "$server/series/$username/$password/$id.$ext"

    fun streamUrl(streamId: Int, hls: Boolean): String =
        "$server/live/$username/$password/$streamId." + if (hls) "m3u8" else "ts"
}

/** One programme in the TV guide. */
data class Programme(val title: String, val description: String?, val start: Long, val end: Long)

/** Holds the grouped channel list in memory while the app is open. */
object Library {
    @Volatile
    var data: GroupedLibrary? = null
        private set
    private val byKey = HashMap<String, ChannelGroup>()

    @Synchronized
    fun channel(key: String): ChannelGroup? = byKey[key]

    /** Channels in categories you haven't hidden (Settings → Live TV categories). Favourites always show. */
    fun visible(prefs: Prefs): List<ChannelGroup> {
        val lib = data ?: return emptyList()
        val hidden = prefs.hiddenLiveCategories
        if (hidden.isEmpty()) return lib.channels
        val favs = prefs.favourites
        return lib.channels.filter { ch -> ch.key in favs || ch.categoryKeys.isEmpty() || ch.categoryKeys.any { it !in hidden } }
    }

    @Synchronized
    fun clear() {
        byKey.clear()
        data = null
    }

    private val loadLock = Any()

    /** One load at a time: a second caller waits for the first and gets its result. */
    fun load(api: XtreamApi): GroupedLibrary = synchronized(loadLock) { loadLocked(api) }

    private fun loadLocked(api: XtreamApi): GroupedLibrary {
        data?.let { return it }
        val cats = api.categories()
        val streams = api.streams()
        if (streams.isEmpty()) throw ApiException("Your account has no live channels")
        val lib = ChannelGrouper.group(cats, streams)
        synchronized(this) {
            byKey.clear()
            lib.channels.forEach { byKey[it.key] = it }
            data = lib
        }
        return lib
    }
}

/** TV shows, loaded the first time the TV Shows tab is opened. */
object Shows {
    @Volatile
    var data: ShowLibrary? = null
        private set
    private val byKey = HashMap<String, Show>()
    private val episodeCache = HashMap<String, List<Episode>>()

    /** The episode list the player is working through (for "next episode"). */
    var queueShow: Show? = null
    var queue: List<Episode> = emptyList()

    @Synchronized
    fun show(key: String): Show? = byKey[key]

    @Synchronized
    fun clear() {
        byKey.clear()
        episodeCache.clear()
        data = null
        queueShow = null
        queue = emptyList()
    }

    /** Movies played through the episode player are queued as a one-episode "show". */
    @Volatile
    var queueIsMovie = false
    var queueTitleKey: String? = null

    fun load(api: XtreamApi): ShowLibrary {
        val lib = SeriesGrouper.group(api.seriesCategories(), api.series())
        adopt(lib)
        return lib
    }

    @Synchronized
    fun adopt(lib: ShowLibrary) {
        byKey.clear()
        episodeCache.clear()
        lib.shows.forEach { byKey[it.key] = it }
        data = lib
    }

    /** Episodes of a show, with every duplicate copy merged into one episode. */
    fun episodes(api: XtreamApi, show: Show): List<Episode> {
        synchronized(this) { episodeCache[show.key]?.let { return it } }
        val all = ArrayList<EpisodeSource>()
        var firstError: Throwable? = null
        var anyOk = false
        for (id in show.seriesIds) {
            try {
                all += api.seriesEpisodes(id)
                anyOk = true
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (!anyOk && firstError != null) throw firstError
        val merged = SeriesGrouper.mergeEpisodes(all, show.name)
        synchronized(this) { episodeCache[show.key] = merged }
        return merged
    }
}
