package com.cleantv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class CleanTvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        ChannelCache.init(this)
        // If the app ever crashes, keep what happened so it can be shown on the next start.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(e)
                crashFile(this).writeText("Clean TV ${BuildConfigVersion.name(this)} on ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = java.io.File(app.filesDir, "last_crash.txt")
    }

    /** Artwork stays cached between launches so rows fill instantly. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        // Half the memory per picture; posters and backdrops look the same on a TV.
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build() }
        .build()
}
