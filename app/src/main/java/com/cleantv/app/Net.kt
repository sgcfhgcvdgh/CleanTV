package com.cleantv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Background work and a small on-disk cache for the outside services (TMDB, OMDb, Guardian, ESPN). */
object Bg {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        pool.execute { runCatching(block) }
    }

    fun ui(block: () -> Unit) {
        main.post(block)
    }
}

object Net {
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) cacheDir = File(context.cacheDir, "net").apply { mkdirs() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * GET a URL as text. Answers younger than [ttlMs] come from the disk cache; if the network
     * fails, an older cached answer is used rather than nothing.
     */
    fun get(url: String, ttlMs: Long, headers: Map<String, String> = emptyMap()): String? {
        val file = cacheDir?.let { File(it, hash(url)) }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < ttlMs) {
            return runCatching { file.readText() }.getOrNull()
        }
        val fresh = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CleanTV/1.0 (Android TV)")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (fresh != null) {
            file?.let { runCatching { it.writeText(fresh) } }
            return fresh
        }
        return file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    }

    /**
     * Your provider's big lists (films, series, channels), kept on the TV for [ttlMs] so the app
     * starts without downloading them again. If the provider can't be reached, an older copy is used.
     */
    fun provider(key: String, ttlMs: Long, fetch: () -> String): String {
        val file = cacheDir?.let { File(it, "p_" + hash(key)) }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < ttlMs) {
            runCatching { return file.readText() }
        }
        return try {
            val body = fetch()
            if (file != null && body.length > 2) runCatching { file.writeText(body) }
            body
        } catch (e: Exception) {
            file?.takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull() } ?: throw e
        }
    }

    fun forgetProvider(key: String) {
        cacheDir?.let { File(it, "p_" + hash(key)).delete() }
    }

    /** Forget the provider lists (Settings → Refresh, or a different account). */
    fun clearProvider() {
        cacheDir?.listFiles()?.filter { it.name.startsWith("p_") }?.forEach { it.delete() }
    }

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}
