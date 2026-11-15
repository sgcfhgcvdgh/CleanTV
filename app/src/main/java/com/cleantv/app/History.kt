package com.cleantv.app

import org.json.JSONArray
import org.json.JSONObject

/** One thing you watched. Newest first in [History.entries]. */
data class Watched(
    val key: String,
    val kind: Kind,
    val name: String,
    val year: String?,
    val tmdb: String?,
    val genres: List<String>,
    val categoryKeys: Set<String>,
    val time: Long,
    val positionMs: Long,
    val durationMs: Long,
    /** Shows: the episode, e.g. "S2:E4". */
    val episode: String? = null,
    val episodeIndex: Int = -1,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val inProgress: Boolean get() = positionMs > 30_000 && (durationMs <= 0 || positionMs < durationMs - 120_000)
}

/** Watch history kept on the device, used for Continue Watching and recommendations. */
object History {
    private const val MAX = 200
    private var cache: MutableList<Watched>? = null

    @Synchronized
    fun entries(prefs: Prefs): List<Watched> {
        cache?.let { return it.toList() }
        val list = ArrayList<Watched>()
        runCatching {
            val arr = JSONArray(prefs.historyJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val g = o.optJSONArray("genres")
                val c = o.optJSONArray("cats")
                list += Watched(
                    key = o.optString("key"),
                    kind = if (o.optString("kind") == "SHOW") Kind.SHOW else Kind.MOVIE,
                    name = o.optString("name"),
                    year = o.optString("year").ifBlank { null },
                    tmdb = o.optString("tmdb").ifBlank { null },
                    genres = g?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    categoryKeys = c?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
                    time = o.optLong("time"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    episode = o.optString("ep").ifBlank { null },
                    episodeIndex = o.optInt("epIndex", -1),
                )
            }
        }
        cache = list
        return list.toList()
    }

    /** Records that a title was played (moves it to the front). */
    @Synchronized
    fun record(
        prefs: Prefs, t: Title, positionMs: Long, durationMs: Long,
        episode: String? = null, episodeIndex: Int = -1, extraGenres: List<String> = emptyList(),
    ) {
        val list = entries(prefs).toMutableList()
        val old = list.firstOrNull { it.key == t.key }
        list.removeAll { it.key == t.key }
        list.add(
            0, Watched(
                key = t.key, kind = t.kind, name = t.name, year = t.year, tmdb = t.tmdb,
                genres = (t.genres + extraGenres + (old?.genres ?: emptyList())).distinct(),
                categoryKeys = t.categoryKeys, time = System.currentTimeMillis(),
                positionMs = positionMs, durationMs = durationMs,
                episode = episode, episodeIndex = episodeIndex,
            )
        )
        save(prefs, list.take(MAX))
    }

    /** Adds genres learned later (from the details screen) to an existing entry. */
    @Synchronized
    fun addGenres(prefs: Prefs, key: String, genres: List<String>) {
        if (genres.isEmpty()) return
        val list = entries(prefs).toMutableList()
        val i = list.indexOfFirst { it.key == key }
        if (i < 0) return
        list[i] = list[i].copy(genres = (list[i].genres + genres).distinct())
        save(prefs, list)
    }

    @Synchronized
    fun remove(prefs: Prefs, key: String) {
        save(prefs, entries(prefs).filter { it.key != key })
    }

    @Synchronized
    fun clear(prefs: Prefs) = save(prefs, emptyList())

    private fun save(prefs: Prefs, list: List<Watched>) {
        cache = list.toMutableList()
        val arr = JSONArray()
        for (w in list) {
            arr.put(JSONObject().apply {
                put("key", w.key); put("kind", w.kind.name); put("name", w.name)
                put("year", w.year ?: ""); put("tmdb", w.tmdb ?: "")
                put("genres", JSONArray(w.genres)); put("cats", JSONArray(w.categoryKeys.toList()))
                put("time", w.time); put("pos", w.positionMs); put("dur", w.durationMs)
                put("ep", w.episode ?: ""); put("epIndex", w.episodeIndex)
            })
        }
        prefs.historyJson = arr.toString()
    }
}
