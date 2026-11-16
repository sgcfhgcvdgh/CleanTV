package com.cleantv.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Now-and-next guide for live channels, fetched per channel as it comes on screen and kept
 * until its programmes have finished.
 */
object Epg {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<Int, List<Programme>>()
    private val fetchedAt = HashMap<Int, Long>()
    private val inFlight = HashSet<Int>()
    private val waiting = HashMap<Int, MutableList<(List<Programme>) -> Unit>>()

    /** The guide's stream id for a channel (its first version). */
    fun streamOf(g: ChannelGroup): Int? = g.variants.firstOrNull()?.stream?.id

    /** Cached programmes still worth showing, or null if they need fetching. */
    @Synchronized
    fun cached(streamId: Int): List<Programme>? {
        val list = cache[streamId] ?: return null
        val now = System.currentTimeMillis()
        val left = list.filter { it.end > now }
        // Ask again after 30 minutes when there was no guide, or once fewer than 2 programmes remain.
        val age = now - (fetchedAt[streamId] ?: 0L)
        if ((list.isEmpty() && age > 30 * 60_000L) || (list.isNotEmpty() && left.size < 2)) return null
        return left
    }

    fun fetch(prefs: Prefs, g: ChannelGroup, done: (List<Programme>) -> Unit) {
        restoreOnce()
        val id = streamOf(g) ?: return done(emptyList())
        cached(id)?.let { return done(it) }
        synchronized(this) {
            waiting.getOrPut(id) { ArrayList() } += done
            if (!inFlight.add(id)) return
        }
        pool.execute {
            val api = XtreamApi(prefs.server, prefs.username, prefs.password)
            var list = runCatching { api.shortEpg(id) }.getOrDefault(emptyList())
            // Some panels only carry the guide on one quality of a channel.
            if (list.isEmpty()) {
                val other = g.variants.drop(1).firstOrNull()?.stream?.id
                if (other != null) list = runCatching { api.shortEpg(other) }.getOrDefault(emptyList())
            }
            val callbacks: List<(List<Programme>) -> Unit>
            synchronized(this) {
                cache[id] = list
                fetchedAt[id] = System.currentTimeMillis()
                inFlight.remove(id)
                callbacks = waiting.remove(id) ?: emptyList()
            }
            val now = System.currentTimeMillis()
            val left = list.filter { it.end > now }
            main.post { callbacks.forEach { it(left) } }
        }
    }

    /**
     * What's on each channel, fetched together (blocks; call from a background thread).
     * Guides younger than 20 minutes are reused.
     */
    /** What's already known about these channels, without asking the provider. */
    fun cachedOnly(groups: List<ChannelGroup>): Map<String, List<Programme>> {
        restoreOnce()
        val out = HashMap<String, List<Programme>>()
        synchronized(this) {
            for (g in groups) {
                val id = streamOf(g) ?: continue
                cache[id]?.let { out[g.key] = it }
            }
        }
        return out
    }

    fun loadAll(prefs: Prefs, groups: List<ChannelGroup>): Map<String, List<Programme>> {
        restoreOnce()
        val api = XtreamApi(prefs.server, prefs.username, prefs.password)
        val now = System.currentTimeMillis()
        val out = java.util.concurrent.ConcurrentHashMap<String, List<Programme>>()
        val todo = ArrayList<Pair<ChannelGroup, Int>>()
        for (g in groups) {
            val id = streamOf(g) ?: continue
            val fresh = synchronized(this) { cache[id]?.takeIf { now - (fetchedAt[id] ?: 0L) < 20 * 60_000L } }
            if (fresh != null) out[g.key] = fresh else todo += g to id
        }
        if (todo.isNotEmpty()) {
            val ex = Executors.newFixedThreadPool(4)
            try {
                ex.invokeAll(todo.map { (g, id) ->
                    java.util.concurrent.Callable {
                        val list = runCatching { api.shortEpg(id, 10) }.getOrDefault(emptyList())
                        synchronized(this) { cache[id] = list; fetchedAt[id] = System.currentTimeMillis() }
                        out[g.key] = list
                    }
                }, 25, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                ex.shutdownNow()
            }
            save()
        }
        return out
    }

    // ---- Kept on the TV, so a restart doesn't mean fetching every guide again ----

    private var restored = false

    @Synchronized
    private fun restoreOnce() {
        if (restored) return
        restored = true
        val f = Net.file("epg_snapshot.json") ?: return
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3_600_000L) return
        runCatching {
            val root = org.json.JSONObject(f.readText())
            val now = System.currentTimeMillis()
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                val arr = o.getJSONArray("p")
                val list = (0 until arr.length()).map { i ->
                    val p = arr.getJSONArray(i)
                    Programme(p.getString(0), p.optString(1).ifBlank { null }, p.getLong(2), p.getLong(3))
                }.filter { it.end > now }
                val id = k.toIntOrNull() ?: continue
                if (cache[id] == null) {
                    cache[id] = list
                    fetchedAt[id] = o.optLong("t")
                }
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = Net.file("epg_snapshot.json") ?: return
        val now = System.currentTimeMillis()
        runCatching {
            val root = org.json.JSONObject()
            for ((id, list) in cache) {
                val t = fetchedAt[id] ?: continue
                if (now - t > 3 * 3_600_000L) continue
                val arr = org.json.JSONArray()
                list.filter { it.end > now }.forEach { p ->
                    arr.put(org.json.JSONArray().put(p.title).put(p.description ?: "").put(p.start).put(p.end))
                }
                root.put(id.toString(), org.json.JSONObject().put("t", t).put("p", arr))
            }
            f.writeText(root.toString())
        }
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.UK)

    fun time(ms: Long): String = synchronized(clock) { clock.format(Date(ms)) }

    /** 0..1000 through the programme. */
    fun progress(p: Programme, now: Long = System.currentTimeMillis()): Int =
        (((now - p.start).toDouble() / (p.end - p.start)) * 1000).toInt().coerceIn(0, 1000)
}
