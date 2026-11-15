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
            val pool = Library.visible(Prefs(activity)).ifEmpty { lib.channels }
            val found = GameMatcher.find(game, pool.map { it.key to it.name })
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
        val hiddenCount = prefs.hiddenLiveCategories.size
        val options = arrayOf(
            "Live TV categories" + if (hiddenCount > 0) ": $hiddenCount hidden" else ": showing all",
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
                    0 -> Playback.withChannels(activity) { lib ->
                        if (lib == null) Toast.makeText(activity, "Couldn't load your channels", Toast.LENGTH_LONG).show()
                        else chooseCategories(activity, prefs, lib, onRefresh)
                    }
                    1 -> pickQuality(activity, prefs)
                    2 -> pickStreamType(activity, prefs)
                    3 -> editKeys(activity, prefs, onRefresh)
                    4 -> AlertDialog.Builder(activity)
                        .setTitle("Clear watch history?")
                        .setMessage("Continue Watching and your recommendations will start again from scratch.")
                        .setPositiveButton("Clear") { _, _ -> History.clear(prefs); onRefresh() }
                        .setNegativeButton("Cancel", null).show()
                    5 -> {
                        Library.clear(); Shows.clear(); Catalog.clear(); Net.clearProvider()
                        Toast.makeText(activity, "Refreshing…", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                    6 -> {
                        activity.startActivity(Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_RELOGIN, true))
                        activity.finishAffinity()
                    }
                }
            }
            .show()
    }

    /** Tick the Live TV categories to show; untick to hide. */
    private fun chooseCategories(activity: Activity, prefs: Prefs, lib: GroupedLibrary, onRefresh: () -> Unit) {
        val cats = lib.categories
        val hidden = prefs.hiddenLiveCategories
        val checked = BooleanArray(cats.size) { cats[it].key !in hidden }
        val names = cats.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Live TV categories to show")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                prefs.hiddenLiveCategories = cats.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet()
                onRefresh()
            }
            .setNeutralButton("Keep only…") { _, _ -> keepOnly(activity, prefs, lib, onRefresh) }
            .setNegativeButton("Show all") { _, _ ->
                prefs.hiddenLiveCategories = emptySet()
                onRefresh()
            }
            .show()
    }

    /** "UK, sport" hides every category whose name doesn't mention UK or sport. */
    private fun keepOnly(activity: Activity, prefs: Prefs, lib: GroupedLibrary, onRefresh: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = "e.g. UK, sport"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("Keep only categories that mention…")
            .setMessage("Separate words with commas. Everything else is hidden; you can fine-tune the list afterwards.")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val keep = keepOnlyKeys(lib.categories.map { it.key to it.name }, input.text.toString())
                if (keep.isEmpty()) {
                    Toast.makeText(activity, "No categories mention that, nothing was hidden", Toast.LENGTH_LONG).show()
                } else {
                    prefs.hiddenLiveCategories = lib.categories.map { it.key }.filter { it !in keep }.toSet()
                    Toast.makeText(activity, "Showing ${keep.size} of ${lib.categories.size} categories", Toast.LENGTH_SHORT).show()
                    onRefresh()
                    chooseCategories(activity, prefs, lib, onRefresh)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Category keys whose names contain any of the comma-separated words, as whole words ("UK" not "Ukraine"). */
    fun keepOnlyKeys(categories: List<Pair<String, String>>, words: String): Set<String> {
        val terms = words.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptySet()
        val patterns = terms.map { Regex("(?i)(^|[^\\p{L}\\p{N}])" + Regex.escape(it) + "s?($|[^\\p{L}\\p{N}])") }
        return categories.filter { (_, name) -> patterns.any { it.containsMatchIn(name) } }.map { it.first }.toSet()
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
