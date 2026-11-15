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

    private val clock = SimpleDateFormat("HH:mm", Locale.UK)

    fun time(ms: Long): String = synchronized(clock) { clock.format(Date(ms)) }

    /** 0..1000 through the programme. */
    fun progress(p: Programme, now: Long = System.currentTimeMillis()): Int =
        (((now - p.start).toDouble() / (p.end - p.start)) * 1000).toInt().coerceIn(0, 1000)
}
