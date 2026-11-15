package com.cleantv.app

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Starting playback of movies, episodes and sports, shared by every screen. */
object Playback {

    fun playMovie(activity: Activity, t: Title) {
        val sources = t.ids.mapIndexed { i, id ->
            EpisodeSource(id, Catalog.movieExt(id), t.name, 0, 0, null, null, i)
        }
        if (sources.isEmpty()) return
        val labels = sources.mapIndexed { i, s -> "Copy ${i + 1}  ·  ${s.ext.uppercase()}" }
        Shows.queueShow = Show(t.key, t.label, t.ids, t.categoryKeys, t.poster, t.order)
        Shows.queue = listOf(Episode(0, 0, t.name, sources, labels))
        Shows.queueIsMovie = true
        Shows.queueTitleKey = t.key
        activity.startActivity(Intent(activity, VodPlayerActivity::class.java).putExtra(VodPlayerActivity.EXTRA_INDEX, 0))
    }

    /** Plays a show from an episode (or the first one). Loads the episode list first. */
    fun playShow(activity: Activity, t: Title, episodeIndex: Int = 0, onError: (Throwable) -> Unit = {}) {
        val show = Shows.show(t.ref) ?: return
        val prefs = Prefs(activity)
        Toast.makeText(activity, "Loading ${t.name}…", Toast.LENGTH_SHORT).show()
        Bg.run {
            val result = runCatching { Shows.episodes(XtreamApi(prefs.server, prefs.username, prefs.password), show) }
            Bg.ui {
                if (activity.isFinishing) return@ui
                result.onSuccess { eps ->
                    if (eps.isEmpty()) {
                        Toast.makeText(activity, "${t.name} has no episodes yet", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    startEpisodes(activity, t, show, eps, episodeIndex.coerceIn(0, eps.size - 1))
                }.onFailure(onError)
            }
        }
    }

    fun startEpisodes(activity: Activity, t: Title?, show: Show, eps: List<Episode>, index: Int) {
        Shows.queueShow = show
        Shows.queue = eps
        Shows.queueIsMovie = false
        Shows.queueTitleKey = t?.key ?: ("s:" + show.key)
        activity.startActivity(Intent(activity, VodPlayerActivity::class.java).putExtra(VodPlayerActivity.EXTRA_INDEX, index))
    }

    fun openDetails(activity: Activity, t: Title) {
        activity.startActivity(Intent(activity, DetailsActivity::class.java).putExtra(DetailsActivity.EXTRA_KEY, t.key))
    }

    fun playChannel(activity: Activity, key: String, list: List<String>) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_KEY, key)
                .putStringArrayListExtra(PlayerActivity.EXTRA_LIST, ArrayList(list.ifEmpty { listOf(key) }))
        )
    }

    /** Channel list for matching games, loading it first if needed. */
    fun withChannels(activity: Activity, block: (GroupedLibrary?) -> Unit) {
        Library.data?.let { block(it); return }
        val prefs = Prefs(activity)
        Bg.run {
            val lib = runCatching { Library.load(XtreamApi(prefs.server, prefs.username, prefs.password)) }.getOrNull()
            Bg.ui { if (!activity.isFinishing) block(lib) }
        }
    }

    /**
     * Opens a game: plays the channel that's showing it, or asks which of the likely
     * channels to try. Up/Down in the player then flicks through the other candidates.
     */
    fun watchGame(activity: Activity, game: Game) {
        if (game.state == GameState.FINISHED) {
            Toast.makeText(activity, "${game.title} has finished", Toast.LENGTH_SHORT).show()
            return
        }
        withChannels(activity) { lib ->
            if (lib == null) {
                Toast.makeText(activity, "Couldn't load your channels", Toast.LENGTH_LONG).show()
                return@withChannels
            }
            val found = GameMatcher.find(game, lib.channels.map { it.key to it.name })
            val keys = found.map { it.key }
            val best = found.firstOrNull()
            when {
                best != null && best.score >= 100 -> {
                    Toast.makeText(activity, "${game.title} on ${best.name}", Toast.LENGTH_SHORT).show()
                    playChannel(activity, best.key, keys)
                }
                found.isNotEmpty() -> {
                    val top = found.take(20)
                    val labels = top.map { "${it.name}\n${it.reason}" }.toTypedArray()
                    AlertDialog.Builder(activity)
                        .setTitle("Watch ${game.title} on…")
                        .setItems(labels) { _, which -> playChannel(activity, top[which].key, keys) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                else -> AlertDialog.Builder(activity)
                    .setTitle(game.title)
                    .setMessage(
                        "None of your channels mention this game yet." +
                            (if (game.broadcasts.isNotEmpty()) "\n\nIt's listed on: ${game.broadcasts.joinToString(", ")}." else "") +
                            "\n\nEvent channels usually appear in your provider's list shortly before kick-off."
                    )
                    .setPositiveButton("Search channels") { _, _ ->
                        activity.startActivity(Intent(activity, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_SEARCH, game.league.name))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }
}

/** The Settings menu, opened from every main screen. */
object SettingsDialog {

    fun show(activity: Activity, onRefresh: () -> Unit) {
        val prefs = Prefs(activity)
        val q = prefs.defaultQuality
        val options = arrayOf(
            "Start in quality: ${if (q == Quality.UHD) "UHD" else q.label}",
            "Live stream type: ${if (prefs.useHls) "HLS (.m3u8)" else "MPEG-TS (.ts)"}",
            "Critic reviews and artwork keys",
            "Clear watch history",
            "Refresh movies, shows and channels",
            "Log out / change account",
        )
        AlertDialog.Builder(activity)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickQuality(activity, prefs)
                    1 -> pickStreamType(activity, prefs)
                    2 -> editKeys(activity, prefs, onRefresh)
                    3 -> AlertDialog.Builder(activity)
                        .setTitle("Clear watch history?")
                        .setMessage("Continue Watching and your recommendations will start again from scratch.")
                        .setPositiveButton("Clear") { _, _ -> History.clear(prefs); onRefresh() }
                        .setNegativeButton("Cancel", null).show()
                    4 -> {
                        Library.clear(); Shows.clear(); Catalog.clear()
                        Toast.makeText(activity, "Refreshing…", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                    5 -> {
                        activity.startActivity(Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_RELOGIN, true))
                        activity.finishAffinity()
                    }
                }
            }
            .show()
    }

    private fun pickQuality(activity: Activity, prefs: Prefs) {
        val choices = listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD)
        val labels = arrayOf("SD", "HD (recommended)", "FHD", "UHD / 4K")
        AlertDialog.Builder(activity)
            .setTitle("Start channels and episodes in")
            .setSingleChoiceItems(labels, choices.indexOf(prefs.defaultQuality).coerceAtLeast(1)) { d, which ->
                prefs.defaultQuality = choices[which]; d.dismiss()
            }
            .show()
    }

    private fun pickStreamType(activity: Activity, prefs: Prefs) {
        val labels = arrayOf("MPEG-TS (.ts) – works with most providers", "HLS (.m3u8) – try if TS buffers or won't play")
        AlertDialog.Builder(activity)
            .setTitle("Live stream type")
            .setSingleChoiceItems(labels, if (prefs.useHls) 1 else 0) { d, which ->
                prefs.useHls = which == 1; d.dismiss()
            }
            .show()
    }

    private fun editKeys(activity: Activity, prefs: Prefs, onRefresh: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String) = EditText(activity).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            box.addView(this)
        }
        val omdb = field("OMDb key (Rotten Tomatoes, Metacritic, IMDb)", prefs.omdbKey)
        val tmdb = field("TMDB key or token (artwork, \"because you watched\")", prefs.tmdbKey)
        val guardian = field("Guardian key (critic reviews; blank = public test key)",
            prefs.guardianKey.takeIf { it != "test" } ?: "")
        AlertDialog.Builder(activity)
            .setTitle("Critic reviews and artwork")
            .setMessage("Free keys: omdbapi.com/apikey.aspx · themoviedb.org/settings/api · open-platform.theguardian.com")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.omdbKey = omdb.text.toString()
                prefs.tmdbKey = tmdb.text.toString()
                prefs.guardianKey = guardian.text.toString()
                onRefresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
