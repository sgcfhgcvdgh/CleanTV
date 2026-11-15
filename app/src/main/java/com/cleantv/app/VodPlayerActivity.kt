package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Plays films and TV episodes, through VLC by default (ExoPlayer from the options).
 *  OK           -> pause / play
 *  ◀ / ▶        -> back 10s / forward 10s (hold to go faster)
 *  ☰ Menu       -> options: copy, player, audio, subtitles, picture, next episode
 *  ⏭ / ⏮        -> next / previous episode
 * Carries on to the next episode, remembers where you stopped, and recovers by itself when
 * the picture freezes: reconnect at the same point, then VLC, then another copy.
 */
class VodPlayerActivity : AppCompatActivity(), LiveEngine.Listener {

    companion object {
        const val EXTRA_INDEX = "index"

        private const val FROZEN_MS = 6_000L
        private const val STALLED_MS = 20_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var vlcLayout: VLCVideoLayout
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySource: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loading: ProgressBar
    private lateinit var pauseMark: View
    private lateinit var errorText: TextView
    private lateinit var toast: TextView

    private var engine: LiveEngine? = null
    private var engineType = LiveEngine.VLC
    private var show: Show? = null
    private var queue: List<Episode> = emptyList()
    private var index = 0
    private var order: List<Int> = emptyList()   // copy indices in the order to try
    private var sourceIdx = 0                     // index into episode.sources
    private val failed = HashSet<Int>()           // copy indices that failed for this episode
    private var pendingSeekMs = 0L
    private var lastKnownPos = 0L
    private var dialog: AlertDialog? = null

    // Seeking: presses add up, then one seek is made.
    private var seekTarget = -1L
    // Watchdog
    private var lastFrames = -1
    private var lastPos = -1L
    private var stuckSince = 0L
    private var bufferingSince = 0L
    private var recoveries = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        if (engine?.isPaused != true) overlay.animate().alpha(0f).setDuration(400).start()
    }
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(300).start() }
    private val saveProgress = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 10_000)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val doSeek = Runnable {
        val target = seekTarget
        seekTarget = -1L
        if (target >= 0) engine?.seekTo(target)
    }

    private val episode: Episode? get() = queue.getOrNull(index)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        api = XtreamApi(prefs.server, prefs.username, prefs.password)
        engineType = prefs.liveEngine
        playerView = findViewById(R.id.playerView)
        vlcLayout = findViewById(R.id.vlcLayout)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlaySource = findViewById(R.id.overlaySource)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        progress = findViewById(R.id.progress)
        loading = findViewById(R.id.loading)
        pauseMark = findViewById(R.id.pauseMark)
        errorText = findViewById(R.id.errorText)
        toast = findViewById(R.id.toast)
        playerView.useController = false
        playerView.isFocusable = false

        show = Shows.queueShow
        queue = Shows.queue
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (show == null || episode == null) {
            finish()
            return
        }
        startEpisode(index, resume = true)
    }

    override fun onStart() {
        super.onStart()
        if (engine == null) createEngine()
        engine?.start()
        if (episode != null) playSource(sourceIdx, seekMs = pendingSeekMs)
        handler.postDelayed(saveProgress, 10_000)
        handler.postDelayed(watchdog, 2_000)
        handler.post(ticker)
    }

    override fun onStop() {
        savePosition()
        handler.removeCallbacks(saveProgress)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(ticker)
        pendingSeekMs = currentPos()
        engine?.release()
        engine = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun createEngine() {
        engine = runCatching {
            if (engineType == LiveEngine.VLC) VlcEngine(this, vlcLayout, this) else ExoEngine(this, playerView, this)
        }.getOrElse {
            engineType = LiveEngine.EXO
            ExoEngine(this, playerView, this)
        }
        if (engineType == LiveEngine.VLC) playerView.visibility = View.GONE else vlcLayout.visibility = View.GONE
        engine?.setAspect(prefs.aspect)
    }

    private fun switchEngine(type: Int, why: String?) {
        val pos = currentPos()
        engine?.release()
        engine = null
        engineType = type
        createEngine()
        engine?.start()
        why?.let { say(it) }
        recoveries = 0
        playSource(sourceIdx, seekMs = pos)
    }

    private fun currentPos(): Long = engine?.positionMs()?.takeIf { it > 0 }?.also { lastKnownPos = it } ?: lastKnownPos

    // ------------------------------------------------------------------ engine events

    override fun onReady() {
        loading.visibility = View.GONE
        errorText.visibility = View.GONE
        updateOverlay()
    }

    override fun onBuffering() {
        loading.visibility = View.VISIBLE
    }

    override fun onEnded() = onEpisodeFinished()

    override fun onTracksChanged() = updateOverlay()

    override fun onError(behindLive: Boolean) = onSourceFailed()

    // ------------------------------------------------------------------ playing

    /** Opens an episode and picks the copy to start with. */
    private fun startEpisode(i: Int, resume: Boolean) {
        index = i
        val e = episode ?: return
        failed.clear()
        recoveries = 0
        order = SeriesGrouper.playOrder(e, prefs.defaultQuality)
        sourceIdx = order.firstOrNull() ?: 0
        val saved = if (resume) prefs.resumePosition(resumeKey(show!!, e)) else 0L
        pendingSeekMs = saved
        lastKnownPos = saved
        if (saved > 30_000) say("Carrying on from ${formatTime(saved)}")
        errorText.visibility = View.GONE
        if (engine != null) playSource(sourceIdx, seekMs = saved)
        recordHistory(saved, e.sources.getOrNull(sourceIdx)?.durationSecs?.let { it * 1000L } ?: 0L)
        updateOverlay()
        showOverlay()
    }

    private fun playSource(idx: Int, seekMs: Long) {
        val e = episode ?: return
        val src = e.sources.getOrNull(idx) ?: return
        sourceIdx = idx
        val url = if (Shows.queueIsMovie) api.movieUrl(src.id, src.ext) else api.episodeUrl(src.id, src.ext)
        resetWatchdog()
        loading.visibility = View.VISIBLE
        pauseMark.visibility = View.GONE
        engine?.play(url, hls = false, live = false, startMs = seekMs)
        pendingSeekMs = 0L
        updateOverlay()
    }

    private fun onSourceFailed() {
        val e = episode ?: return
        val position = currentPos()
        failed += sourceIdx
        val next = order.firstOrNull { it !in failed }
        when {
            next != null -> {
                say("${label(sourceIdx)} won't play – trying ${label(next)}")
                playSource(next, seekMs = position)
                showOverlay()
            }
            engineType == LiveEngine.EXO -> {
                failed.clear()
                switchEngine(LiveEngine.VLC, "Trying the VLC player")
            }
            else -> {
                loading.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                errorText.text = buildString {
                    append("This won't play right now.\n\n")
                    if (e.sources.size > 1) append("All ${e.sources.size} copies were tried. ")
                    append("Press ☰ Menu to try a copy or the other player, or Back to return.")
                }
            }
        }
    }

    private fun onEpisodeFinished() {
        val e = episode ?: return
        prefs.saveResumePosition(resumeKey(show!!, e), 0L)
        if (!Shows.queueIsMovie && index + 1 < queue.size) {
            val n = queue[index + 1]
            say("Next: ${episodeName(n)}")
            startEpisode(index + 1, resume = true)
        } else {
            finish()
        }
    }

    // ------------------------------------------------------------------ watchdog

    private fun resetWatchdog() {
        lastFrames = -1; lastPos = -1L; stuckSince = 0L; bufferingSince = 0L
    }

    /** Every 2 seconds: is the picture moving? If not, pick up again from the same point. */
    private val watchdog = object : Runnable {
        override fun run() {
            val e = engine
            if (e != null && !isFinishing && !e.isPaused && seekTarget < 0) {
                val now = SystemClock.elapsedRealtime()
                when {
                    e.isReady -> {
                        bufferingSince = 0L
                        val frames = e.framesShown()
                        val pos = e.positionMs()
                        val moving = if (e.hasVideo() && frames >= 0) frames != lastFrames else pos != lastPos
                        if (!moving && (lastFrames != -1 || lastPos != -1L)) {
                            if (stuckSince == 0L) stuckSince = now
                            else if (now - stuckSince > FROZEN_MS) recover("The picture froze")
                        } else stuckSince = 0L
                        lastFrames = frames
                        lastPos = pos
                    }
                    e.isBuffering -> {
                        if (bufferingSince == 0L) bufferingSince = now
                        else if (now - bufferingSince > STALLED_MS) recover("It stopped loading")
                    }
                    else -> {
                        bufferingSince = 0L; stuckSince = 0L
                    }
                }
            }
            handler.postDelayed(this, 2_000)
        }
    }

    private fun recover(why: String) {
        recoveries++
        val pos = currentPos()
        resetWatchdog()
        when {
            recoveries >= 2 && engineType == LiveEngine.EXO -> switchEngine(LiveEngine.VLC, "$why – switching to the VLC player")
            recoveries <= 2 -> {
                say("$why – picking up again")
                playSource(sourceIdx, seekMs = pos)
            }
            else -> {
                recoveries = 0
                onSourceFailed()
            }
        }
    }

    // ------------------------------------------------------------------ controls

    private fun togglePause() {
        val e = engine ?: return
        val pause = !e.isPaused
        e.setPaused(pause)
        pauseMark.visibility = if (pause) View.VISIBLE else View.GONE
        if (pause) savePosition()
        showOverlay()
    }

    /** Adds up presses (and holds) into one jump: 10s, then 30s, then a minute at a time. */
    private fun seekBy(direction: Int, repeat: Int) {
        val e = engine ?: return
        val step = when {
            repeat > 12 -> 60_000L
            repeat > 3 -> 30_000L
            else -> 10_000L
        }
        val base = if (seekTarget >= 0) seekTarget else currentPos()
        val dur = e.durationMs()
        seekTarget = (base + direction * step).coerceIn(0L, if (dur > 0) dur - 5_000 else Long.MAX_VALUE)
        handler.removeCallbacks(doSeek)
        handler.postDelayed(doSeek, 450)
        showOverlay()
        updateProgress()
    }

    private fun updateProgress() {
        val e = engine ?: return
        if (overlay.alpha == 0f) return
        val dur = e.durationMs()
        val pos = if (seekTarget >= 0) seekTarget else currentPos()
        positionText.text = formatTime(pos)
        durationText.text = if (dur > 0) "-" + formatTime((dur - pos).coerceAtLeast(0)) else ""
        progress.progress = if (dur > 0) ((pos * 1000) / dur).toInt() else 0
    }

    // ------------------------------------------------------------------ options

    private class Opt(val label: String, val action: () -> Unit)

    private fun showOptions() {
        val e = episode ?: return
        val eng = engine
        val audio = eng?.audioTracks() ?: emptyList()
        val subs = eng?.subtitleTracks() ?: emptyList()
        val items = ArrayList<Opt>()
        if (e.sources.size > 1) items += Opt("Copy: ${label(sourceIdx)}  (${e.sources.size} copies)") { showSourcePicker() }
        items += Opt("Player: ${if (engineType == LiveEngine.VLC) "VLC" else "ExoPlayer"}") {
            val type = if (engineType == LiveEngine.VLC) LiveEngine.EXO else LiveEngine.VLC
            prefs.liveEngine = type
            switchEngine(type, "Switched to ${if (type == LiveEngine.VLC) "VLC" else "ExoPlayer"}")
        }
        if (audio.size > 1) items += Opt("Audio: ${audio.firstOrNull { it.selected }?.label ?: "Auto"}") { pickTrack(true, audio) }
        if (subs.isNotEmpty()) items += Opt("Subtitles: ${if (eng?.subtitlesOff() != false) "Off" else subs.firstOrNull { it.selected }?.label ?: "On"}") { pickTrack(false, subs) }
        items += Opt("Picture: ${when (prefs.aspect) { 1 -> "Zoom to fill"; 2 -> "Stretch"; else -> "Fit" }}") { pickAspect() }
        if (!Shows.queueIsMovie && index + 1 < queue.size) items += Opt("Next episode: ${episodeName(queue[index + 1])}") {
            savePosition(); startEpisode(index + 1, resume = true)
        }
        items += Opt("Start from the beginning") { engine?.seekTo(0L); showOverlay() }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (Shows.queueIsMovie) show?.name ?: "" else episodeName(e))
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() }
            .show()
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
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Choose copy")
            .setSingleChoiceItems(labels, sourceIdx) { d, which ->
                d.dismiss()
                failed.remove(which)
                errorText.visibility = View.GONE
                playSource(which, seekMs = currentPos())
                showOverlay()
            }
            .show()
    }

    private fun pickTrack(audio: Boolean, list: List<EngineTrack>) {
        val e = engine ?: return
        val labels = (if (audio) emptyList() else listOf("Off")) + list.map { it.label }
        val selected = list.indexOfFirst { it.selected }
        val sel = if (audio) selected.coerceAtLeast(0) else if (e.subtitlesOff()) 0 else selected + 1
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (audio) "Audio" else "Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), sel.coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (audio) e.selectAudio(list[which])
                else e.selectSubtitle(if (which == 0) null else list[which - 1])
            }
            .show()
    }

    private fun pickAspect() {
        val labels = arrayOf("Fit (whole picture)", "Zoom to fill (crops edges)", "Stretch")
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Picture")
            .setSingleChoiceItems(labels, prefs.aspect) { d, which ->
                d.dismiss()
                prefs.aspect = which
                engine?.setAspect(which)
            }
            .show()
    }

    // ------------------------------------------------------------------ progress and history

    private fun savePosition() {
        val e = episode ?: return
        val s = show ?: return
        val eng = engine ?: return
        val pos = currentPos()
        val dur = eng.durationMs()
        // Nearly finished counts as watched.
        val keep = pos > 30_000 && (dur <= 0 || pos < dur - 120_000)
        prefs.saveResumePosition(resumeKey(s, e), if (keep) pos else 0L)
        recordHistory(pos, dur)
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

    private fun label(i: Int): String = episode?.labels?.getOrNull(i)?.substringBefore("  ·") ?: "Copy ${i + 1}"

    private fun episodeName(e: Episode): String {
        val num = if (e.season > 0 && e.episode > 0) "S%02dE%02d".format(e.season, e.episode) else ""
        return listOf(num, e.title).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Episode" }
    }

    private fun updateOverlay() {
        val e = episode ?: return
        overlayTitle.text = if (Shows.queueIsMovie) show?.name ?: "" else "${show?.name ?: ""}  ·  ${episodeName(e)}"
        val bits = ArrayList<String>()
        if (e.sources.size > 1) bits += "${label(sourceIdx)} of ${e.sources.size}"
        engine?.videoInfo()?.let { bits += it }
        bits += if (engineType == LiveEngine.VLC) "VLC" else "ExoPlayer"
        overlaySource.text = bits.joinToString("  ·  ")
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().cancel()
        overlay.alpha = 1f
        updateProgress()
        handler.postDelayed(hideOverlay, 4000)
    }

    private fun say(text: String) {
        toast.text = text
        handler.removeCallbacks(hideToast)
        toast.animate().cancel()
        toast.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed(hideToast, 3500)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    if (event.repeatCount == 0) togglePause(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (engine?.isPaused == true) togglePause(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (engine?.isPaused == false) togglePause(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-1, event.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(1, event.repeatCount); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-1, 5); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(1, 5); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                    updateOverlay(); showOverlay(); return true
                }
                KeyEvent.KEYCODE_MENU -> { showOptions(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (!Shows.queueIsMovie && index + 1 < queue.size) { savePosition(); startEpisode(index + 1, resume = true) }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (!Shows.queueIsMovie && index > 0) { savePosition(); startEpisode(index - 1, resume = true) }
                    return true
                }
            }
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        ) return true
        return super.dispatchKeyEvent(event)
    }
}
