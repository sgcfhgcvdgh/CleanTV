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

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
}
