package com.cleantv.app

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

object BuildConfigVersion {
    fun name(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}

/** Shows the last crash once, so it can be photographed and sent for fixing. */
object CrashReport {
    fun showIfAny(activity: Activity) {
        val f = CleanTvApp.crashFile(activity)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        if (text.isNullOrBlank()) return
        // The first lines of the trace say what went wrong and where.
        val short = text.lines().filter { it.isNotBlank() }.take(14).joinToString("\n")
        AlertDialog.Builder(activity)
            .setTitle("Clean TV closed unexpectedly last time")
            .setMessage("A photo of this helps get it fixed:\n\n$short")
            .setPositiveButton("OK", null)
            .show()
    }
}
