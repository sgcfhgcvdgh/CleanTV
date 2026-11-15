package com.cleantv.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cleantv", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    /** Quality to start channels in. HD unless changed in settings. */
    var defaultQuality: Quality
        get() = Quality.fromPref(sp.getString("quality", null))
        set(v) = sp.edit().putString("quality", v.name).apply()

    /** false = MPEG-TS (.ts, works with most providers), true = HLS (.m3u8). */
    var useHls: Boolean
        get() = sp.getBoolean("hls", false)
        set(v) = sp.edit().putBoolean("hls", v).apply()

    var favourites: Set<String>
        get() = sp.getStringSet("favs", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("favs", HashSet(v)).apply()

    /** Where you stopped watching an episode, so it can carry on from there. */
    fun resumePosition(key: String): Long = sp.getLong("pos:$key", 0L)

    fun saveResumePosition(key: String, positionMs: Long) {
        if (positionMs <= 0L) sp.edit().remove("pos:$key").apply()
        else sp.edit().putLong("pos:$key", positionMs).apply()
    }

    // ---------- Movie data services (optional keys, set in Settings) ----------

    /** TMDB API key (v3) or read access token (v4): artwork and "because you watched" picks. */
    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) = sp.edit().putString("tmdbKey", v.trim()).apply()

    /** OMDb key: Rotten Tomatoes, Metacritic and IMDb scores. */
    var omdbKey: String
        get() = sp.getString("omdbKey", "") ?: ""
        set(v) = sp.edit().putString("omdbKey", v.trim()).apply()

    /** Guardian Open Platform key for full critic reviews. "test" is the Guardian's public developer key. */
    var guardianKey: String
        get() = (sp.getString("guardianKey", "") ?: "").ifBlank { "test" }
        set(v) = sp.edit().putString("guardianKey", v.trim()).apply()

    /** Sport chosen last time on the Sports screen. */
    var lastSport: String
        get() = sp.getString("lastSport", "") ?: ""
        set(v) = sp.edit().putString("lastSport", v).apply()

    // ---------- My List and watch history ----------

    var myList: List<String>
        get() = (sp.getString("myList", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("myList", v.joinToString("\n")).apply()

    fun toggleMyList(key: String): Boolean {
        val list = myList.toMutableList()
        val added = !list.remove(key)
        if (added) list.add(0, key)
        myList = list
        return added
    }

    var historyJson: String
        get() = sp.getString("history", "[]") ?: "[]"
        set(v) = sp.edit().putString("history", v).apply()

    val isLoggedIn: Boolean
        get() = server.isNotBlank() && username.isNotBlank()

    fun logout() {
        sp.edit().remove("server").remove("username").remove("password").apply()
    }
}
