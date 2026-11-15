package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays TV show episodes.
 *  OK / arrows   -> normal player controls (pause, seek)
 *  Menu (☰)      -> choose which copy of the episode to play
 *  ⏭ / ⏮         -> next / previous episode
 * Carries on to the next episode automatically, and remembers where you stopped.
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySource: TextView
    private lateinit var errorText: TextView

    private var player: ExoPlayer? = null
    private var show: Show? = null
    private var queue: List<Episode> = emptyList()
    private var index = 0
    private var order: List<Int> = emptyList()   // copy indices in the order to try
    private var sourceIdx = 0                     // index into episode.sources
    private val failed = HashSet<Int>()           // copy indices that failed for this episode
    private var pendingSeekMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }
    private val saveProgress = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 10_000)
        }
    }

    private val episode: Episode? get() = queue.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        api = XtreamApi(prefs.server, prefs.username, prefs.password)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlaySource = findViewById(R.id.overlaySource)
        errorText = findViewById(R.id.errorText)

        show = Shows.queueShow
        queue = Shows.queue
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (show == null || episode == null) {
            finish()
            return
        }
        playerView.setShowSubtitleButton(true)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) showOverlay(sticky = true) else handler.postDelayed(hideOverlay, 300)
        })
        playerView.requestFocus()
        startEpisode(index, resume = true)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initPlayer()
        if (episode != null) playSource(sourceIdx, seekMs = pendingSeekMs)
        handler.postDelayed(saveProgress, 10_000)
    }

    override fun onStop() {
        savePosition()
        handler.removeCallbacks(saveProgress)
        pendingSeekMs = player?.currentPosition ?: pendingSeekMs
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errorText.visibility = View.GONE
                if (state == Player.STATE_ENDED) onEpisodeFinished()
            }

            override fun onPlayerError(error: PlaybackException) = onSourceFailed(error)
        })
        playerView.player = p
        player = p
    }

    /** Opens an episode and picks the copy to start with. */
    private fun startEpisode(i: Int, resume: Boolean) {
        index = i
        val e = episode ?: return
        failed.clear()
        order = SeriesGrouper.playOrder(e, prefs.defaultQuality)
        sourceIdx = order.firstOrNull() ?: 0
        val saved = if (resume) prefs.resumePosition(resumeKey(show!!, e)) else 0L
        pendingSeekMs = saved
        if (saved > 30_000) Toast.makeText(this, "Carrying on from ${formatTime(saved)}", Toast.LENGTH_SHORT).show()
        errorText.visibility = View.GONE
        if (player != null) playSource(sourceIdx, seekMs = saved)
        recordHistory(saved, e.sources.getOrNull(sourceIdx)?.durationSecs?.let { it * 1000L } ?: 0L)
        updateOverlay()
        showOverlay(sticky = false)
    }

    private fun playSource(idx: Int, seekMs: Long) {
        val e = episode ?: return
        val src = e.sources.getOrNull(idx) ?: return
        sourceIdx = idx
        val p = player ?: return
        val url = if (Shows.queueIsMovie) api.movieUrl(src.id, src.ext) else api.episodeUrl(src.id, src.ext)
        val item = MediaItem.fromUri(url)
        if (seekMs > 0) p.setMediaItem(item, seekMs) else p.setMediaItem(item)
        p.prepare()
        p.play()
        pendingSeekMs = 0L
        updateOverlay()
    }

    private fun onSourceFailed(error: PlaybackException) {
        val e = episode ?: return
        val position = player?.currentPosition ?: 0L
        failed += sourceIdx
        val next = order.firstOrNull { it !in failed }
        if (next != null) {
            Toast.makeText(this, "${label(sourceIdx)} won't play – trying ${label(next)}", Toast.LENGTH_SHORT).show()
            playSource(next, seekMs = position)
            showOverlay(sticky = false)
        } else {
            errorText.visibility = View.VISIBLE
            errorText.text = buildString {
                append("This episode won't play right now (${error.errorCodeName}).\n\n")
                if (e.sources.size > 1) append("All ${e.sources.size} copies were tried. ")
                append("Press ☰ Menu to try a copy again, or Back to return.")
            }
        }
    }

    private fun onEpisodeFinished() {
        val e = episode ?: return
        prefs.saveResumePosition(resumeKey(show!!, e), 0L)
        if (index + 1 < queue.size) {
            val n = queue[index + 1]
            Toast.makeText(this, "Next: ${episodeName(n)}", Toast.LENGTH_SHORT).show()
            startEpisode(index + 1, resume = true)
        } else {
            finish()
        }
    }

    private fun savePosition() {
        val e = episode ?: return
        val s = show ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        // Nearly finished counts as watched.
        val keep = pos > 30_000 && (dur <= 0 || pos < dur - 120_000)
        prefs.saveResumePosition(resumeKey(s, e), if (keep) pos else 0L)
        recordHistory(pos, if (dur > 0) dur else 0L)
    }

    /** Feeds Continue Watching and the recommendations. */
    private fun recordHistory(positionMs: Long, durationMs: Long) {
        val e = episode ?: return
        val t = Shows.queueTitleKey?.let { Catalog.title(it) } ?: return
        if (t.kind == Kind.MOVIE) {
            History.record(prefs, t, positionMs, durationMs)
        } else {
            val finishedShow = index == queue.size - 1 && durationMs > 0 && positionMs >= durationMs - 120_000
            History.record(
                prefs, t, positionMs, durationMs,
                episode = if (finishedShow) null else shortName(e),
                episodeIndex = if (finishedShow) -1 else index,
            )
        }
    }

    private fun shortName(e: Episode): String = when {
        e.season > 0 && e.episode > 0 -> "S${e.season}:E${e.episode}"
        e.episode > 0 -> "E${e.episode}"
        else -> e.title.take(24).ifEmpty { "Episode" }
    }

    private fun showSourcePicker() {
        val e = episode ?: return
        val labels = e.sources.indices.map { i ->
            buildString {
                append(e.labels.getOrElse(i) { "Copy ${i + 1}" })
                if (i == sourceIdx) append("   ● playing")
                else if (i in failed) append("   (didn't play)")
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (e.sources.size > 1) "${episodeName(e)} – choose copy" else "${episodeName(e)} – only one copy")
            .setSingleChoiceItems(labels, sourceIdx) { d, which ->
                d.dismiss()
                failed.remove(which)
                errorText.visibility = View.GONE
                if (which != sourceIdx || player?.playerError != null) {
                    playSource(which, seekMs = player?.currentPosition ?: 0L)
                }
                showOverlay(sticky = false)
            }
            .show()
    }

    private fun label(i: Int): String = episode?.labels?.getOrNull(i)?.substringBefore("  ·") ?: "Copy ${i + 1}"

    private fun episodeName(e: Episode): String {
        val num = if (e.season > 0 && e.episode > 0) "S%02dE%02d".format(e.season, e.episode) else ""
        return listOf(num, e.title).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Episode" }
    }

    private fun updateOverlay() {
        val e = episode ?: return
        overlayTitle.text = if (Shows.queueIsMovie) show?.name ?: "" else "${show?.name ?: ""}  ·  ${episodeName(e)}"
        overlaySource.text = if (e.sources.size > 1) {
            "${e.labels.getOrElse(sourceIdx) { "Copy ${sourceIdx + 1}" }}   (${e.sources.size} copies)"
        } else e.labels.firstOrNull() ?: ""
    }

    private fun showOverlay(sticky: Boolean) {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().cancel()
        overlay.alpha = 1f
        if (!sticky) handler.postDelayed(hideOverlay, 4000)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    showSourcePicker(); return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (index + 1 < queue.size) { savePosition(); startEpisode(index + 1, resume = true) }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (index > 0) { savePosition(); startEpisode(index - 1, resume = true) }
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ) return true
        // Let the player's own controls handle everything else (play/pause, seeking, D-pad).
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
}
