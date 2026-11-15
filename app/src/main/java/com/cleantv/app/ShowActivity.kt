package com.cleantv.app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlin.concurrent.thread

/** One TV show: seasons on the left, episodes (duplicates already merged) on the right. */
class ShowActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW = "show"
    }

    private lateinit var prefs: Prefs
    private lateinit var show: Show
    private lateinit var seasonAdapter: CategoryAdapter
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var seasonList: RecyclerView
    private lateinit var episodeList: RecyclerView
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private var episodes: List<Episode> = emptyList()
    private var currentSeason: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = intent.getStringExtra(EXTRA_SHOW)?.let { Shows.show(it) }
        if (s == null) {
            finish()
            return
        }
        show = s
        prefs = Prefs(this)
        setContentView(R.layout.activity_show)

        findViewById<TextView>(R.id.showName).text = show.name
        val poster = findViewById<ImageView>(R.id.poster)
        if (show.cover != null) poster.load(show.cover) { error(R.drawable.ic_tv) } else poster.setImageResource(R.drawable.ic_tv)

        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        seasonList = findViewById(R.id.seasons)
        episodeList = findViewById(R.id.episodes)

        seasonAdapter = CategoryAdapter { item, _ -> showSeason(item.key.toInt()) }
        episodeAdapter = EpisodeAdapter(
            onClick = { play(it) },
            hasProgress = { prefs.resumePosition(resumeKey(show, it)) > 0L },
        )
        seasonList.layoutManager = LinearLayoutManager(this)
        seasonList.itemAnimator = null
        seasonList.adapter = seasonAdapter
        episodeList.layoutManager = LinearLayoutManager(this)
        episodeList.itemAnimator = null
        episodeList.adapter = episodeAdapter

        load()
    }

    override fun onResume() {
        super.onResume()
        // Refresh "in progress" markers after watching.
        if (::episodeAdapter.isInitialized) episodeAdapter.notifyDataSetChanged()
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Loading episodes…"
        thread {
            val api = XtreamApi(prefs.server, prefs.username, prefs.password)
            val result = runCatching { Shows.episodes(api, show) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { showEpisodes(it) }.onFailure { e ->
                    status.text = XtreamApi.friendlyError(e)
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't load episodes")
                        .setMessage(XtreamApi.friendlyError(e))
                        .setPositiveButton("Try again") { _, _ -> load() }
                        .setNegativeButton("Back") { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    private fun showEpisodes(list: List<Episode>) {
        episodes = list
        if (list.isEmpty()) {
            status.text = "This show has no episodes yet"
            return
        }
        status.visibility = View.GONE
        val seasons = list.groupBy { it.season }
        val items = seasons.map { (season, eps) ->
            CatItem(season.toString(), if (season > 0) "Season $season" else "Specials", eps.size)
        }
        val first = items.first().key
        seasonAdapter.submit(items, first)
        showSeason(first.toInt())
        seasonList.post {
            seasonList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showSeason(season: Int) {
        if (currentSeason == season) return
        currentSeason = season
        val eps = episodes.filter { it.season == season }
        val dupes = eps.count { it.sources.size > 1 }
        heading.text = (if (season > 0) "Season $season" else "Specials") + "  ·  ${eps.size} episodes" +
            if (dupes > 0) "  ·  $dupes duplicates merged" else ""
        episodeAdapter.submit(eps)
        episodeList.scrollToPosition(0)
    }

    private fun play(e: Episode) {
        Playback.startEpisodes(this, Catalog.title("s:" + show.key), show, episodes, episodes.indexOf(e))
    }
}

fun resumeKey(show: Show, e: Episode): String = show.key + "|" + e.season + ":" + e.episode + ":" + e.title
