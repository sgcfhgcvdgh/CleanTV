package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.util.Locale

/**
 * Live TV player.
 *  OK / Menu   -> options (quality, audio, subtitles, picture, stream type, favourite, reload)
 *  ◀           -> channel list with what's on
 *  ▲ / ▼       -> previous / next channel
 *  ▶ / Info    -> info bar (now and next)
 *
 * It watches itself: if the picture freezes or the stream stalls it reconnects, then tries
 * another quality, then the other stream type, before giving up.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_LIST = "list"

        private const val FROZEN_MS = 8_000L
        private const val STALLED_MS = 15_000L
        private const val STEADY_MS = 60_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayLogo: ImageView
    private lateinit var overlayName: TextView
    private lateinit var overlayQuality: TextView
    private lateinit var overlayPos: TextView
    private lateinit var nowTime: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowDesc: TextView
    private lateinit var nextText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var toast: TextView
    private lateinit var listPanel: LinearLayout
    private lateinit var channelList: RecyclerView
    private lateinit var listAdapter: ChannelListAdapter

    private var player: ExoPlayer? = null
    private var keys: List<String> = emptyList()
    private var index = 0
    private var group: ChannelGroup? = null
    private var order: List<Variant> = emptyList()
    private var current: Variant? = null
    private val failed = HashSet<Int>()
    private var reachedReady = false
    private var dialog: AlertDialog? = null

    /** Stream type for this session; flips automatically if one type keeps failing. */
    private var hls = false
    private var retries = 0
    private var recoveries = 0

    // Watchdog state
    private var lastFrames = -1
    private var lastPos = -1L
    private var stuckSince = 0L
    private var bufferingSince = 0L
    private var readySince = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }
    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(300).start() }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        api = XtreamApi(prefs.server, prefs.username, prefs.password)
        hls = prefs.useHls
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        overlayLogo = findViewById(R.id.overlayLogo)
        overlayName = findViewById(R.id.overlayName)
        overlayQuality = findViewById(R.id.overlayQuality)
        overlayPos = findViewById(R.id.overlayPos)
        nowTime = findViewById(R.id.nowTime)
        nowTitle = findViewById(R.id.nowTitle)
        nowProgress = findViewById(R.id.nowProgress)
        nowDesc = findViewById(R.id.nowDesc)
        nextText = findViewById(R.id.next)
        loading = findViewById(R.id.loading)
        errorText = findViewById(R.id.errorText)
        toast = findViewById(R.id.toast)
        listPanel = findViewById(R.id.listPanel)
        channelList = findViewById(R.id.channelList)

        playerView.useController = false
        playerView.isFocusable = false
        applyAspect()

        val key = intent.getStringExtra(EXTRA_KEY)
        keys = intent.getStringArrayListExtra(EXTRA_LIST) ?: arrayListOf()
        val g = key?.let { Library.channel(it) }
        if (g == null) {
            finish()
            return
        }
        if (keys.isEmpty()) keys = listOf(g.key)
        index = keys.indexOf(key).coerceAtLeast(0)

        listAdapter = ChannelListAdapter(
            prefs,
            onClick = { ch ->
                hideList()
                val i = keys.indexOf(ch.key)
                if (i >= 0) index = i
                if (ch.key != group?.key) openChannel(ch)
            },
            onLongClick = { ch -> toggleFavourite(ch) },
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.itemAnimator = null
        channelList.adapter = listAdapter

        openChannel(g)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initPlayer()
        current?.let { playVariant(it, announce = false) }
        handler.postDelayed(watchdog, 2_000)
    }

    override fun onStop() {
        handler.removeCallbacks(watchdog)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
        // Live TV: start quickly, but keep enough in hand to ride out a wobbly connection.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 40_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // If the TV's preferred decoder can't cope with a stream, fall back to another one.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .build()
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                when (state) {
                    Player.STATE_READY -> {
                        reachedReady = true
                        errorText.visibility = View.GONE
                        if (readySince == 0L) readySince = SystemClock.elapsedRealtime()
                    }
                    // A live stream "ending" means the connection dropped: reconnect.
                    Player.STATE_ENDED -> current?.let { playVariant(it, announce = false) }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) = onStreamFailed(error)

            override fun onTracksChanged(tracks: Tracks) = updateOverlay()
        })
        playerView.player = p
        player = p
    }

    // ------------------------------------------------------------------ playing

    private fun openChannel(g: ChannelGroup) {
        group = g
        failed.clear()
        retries = 0
        recoveries = 0
        hls = prefs.useHls
        order = ChannelGrouper.playOrder(g, prefs.defaultQuality)
        current = order.firstOrNull()
        errorText.visibility = View.GONE
        current?.let { if (player != null) playVariant(it, announce = true) }
        updateOverlay()
        showOverlay()
        loadGuide(g)
    }

    private fun playVariant(v: Variant, announce: Boolean) {
        current = v
        reachedReady = false
        resetWatchdog()
        val item = MediaItem.Builder()
            .setUri(api.streamUrl(v.stream.id, hls))
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        player?.let {
            it.setMediaItem(item)
            it.prepare()
            it.play()
        }
        updateOverlay()
        if (announce) showOverlay()
    }

    private fun onStreamFailed(error: PlaybackException) {
        val cur = current ?: return
        val p = player
        // Fell too far behind the live edge: jump back to live.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        // It was playing, or this is the first hiccup: reconnect a few times with a growing pause.
        if (retries < 3 && (reachedReady || retries == 0)) {
            retries++
            loading.visibility = View.VISIBLE
            handler.postDelayed({ if (current == cur) playVariant(cur, announce = false) }, 800L * retries)
            return
        }
        nextOption(cur, "${cur.label} won't play")
    }

    /** Moves on to the next thing worth trying: another quality, then the other stream type. */
    private fun nextOption(cur: Variant, why: String) {
        failed += cur.stream.id
        retries = 0
        val next = order.firstOrNull { it.stream.id !in failed }
        when {
            next != null -> {
                say("$why – trying ${next.label}")
                playVariant(next, announce = true)
            }
            hls == prefs.useHls -> {
                // Every quality failed one way: try them all again the other way.
                hls = !hls
                failed.clear()
                val first = order.firstOrNull() ?: return
                say("$why – trying ${if (hls) "HLS" else "MPEG-TS"}")
                playVariant(first, announce = true)
            }
            else -> {
                loading.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                errorText.text = "This channel isn't working right now.\n\n" +
                    "Press OK for options, or ▲ ▼ to change channel."
            }
        }
    }

    // ------------------------------------------------------------------ watchdog

    private fun resetWatchdog() {
        lastFrames = -1; lastPos = -1L; stuckSince = 0L; bufferingSince = 0L; readySince = 0L
    }

    /**
     * Every 2 seconds: is the picture actually moving? A frozen frame with the player still
     * "playing" (the decoder has stalled) or endless buffering gets the stream reconnected.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playWhenReady && !isFinishing) {
                val now = SystemClock.elapsedRealtime()
                when (p.playbackState) {
                    Player.STATE_READY -> {
                        bufferingSince = 0L
                        val counters = p.videoDecoderCounters
                        counters?.ensureUpdated()
                        val frames = counters?.renderedOutputBufferCount ?: -1
                        val pos = p.currentPosition
                        val hasVideo = p.videoFormat != null
                        val moving = if (hasVideo && frames >= 0) frames != lastFrames else pos != lastPos
                        if (!moving && lastFrames != -1) {
                            if (stuckSince == 0L) stuckSince = now
                            else if (now - stuckSince > FROZEN_MS) recover("The picture froze")
                        } else stuckSince = 0L
                        lastFrames = frames
                        lastPos = pos
                        // Playing smoothly for a minute: forget earlier trouble.
                        if (readySince > 0 && now - readySince > STEADY_MS) recoveries = 0
                    }
                    Player.STATE_BUFFERING -> {
                        if (bufferingSince == 0L) bufferingSince = now
                        else if (now - bufferingSince > STALLED_MS) recover("The stream stalled")
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
        val cur = current ?: return
        recoveries++
        resetWatchdog()
        if (recoveries <= 2) {
            say("$why – reconnecting")
            playVariant(cur, announce = false)
        } else {
            recoveries = 0
            nextOption(cur, why)
        }
    }

    // ------------------------------------------------------------------ options

    private fun showOptions() {
        val g = group ?: return
        val fav = prefs.favourites.contains(g.key)
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        val items = ArrayList<Opt>()
        items += Opt("Quality: ${current?.label ?: "-"}" + (if (g.variants.size > 1) "  (${g.variants.size} versions)" else "")) { pickQuality() }
        if (audio.size > 1) items += Opt("Audio: ${selected(audio)}") { pickTrack(C.TRACK_TYPE_AUDIO, audio, allowOff = false) }
        if (subs.isNotEmpty()) items += Opt("Subtitles: ${if (isTextOff()) "Off" else selected(subs)}") { pickTrack(C.TRACK_TYPE_TEXT, subs, allowOff = true) }
        items += Opt("Picture: ${aspectName(prefs.aspect)}") { pickAspect() }
        items += Opt("Stream type: ${if (hls) "HLS (.m3u8)" else "MPEG-TS (.ts)"}") {
            hls = !hls
            prefs.useHls = hls
            failed.clear()
            current?.let { playVariant(it, announce = true) }
        }
        items += Opt((if (fav) "Remove from favourites" else "Add to favourites")) { toggleFavourite(g) }
        items += Opt("Reload channel") {
            failed.clear(); retries = 0; recoveries = 0
            current?.let { playVariant(it, announce = true) }
        }
        items += Opt("Channel list") { showList() }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> items[which].action() }
            .show()
    }

    private fun pickQuality() {
        val g = group ?: return
        val variants = g.variants
        val labels = variants.map { v ->
            buildString {
                append(v.label)
                if (v == current) append("   ● playing")
                else if (v.stream.id in failed) append("   (didn't play)")
            }
        }.toTypedArray()
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (variants.size > 1) "${g.name} – quality" else "${g.name} – only one version")
            .setSingleChoiceItems(labels, (current?.let { variants.indexOf(it) } ?: 0).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val v = variants[which]
                failed.remove(v.stream.id)
                errorText.visibility = View.GONE
                playVariant(v, announce = true)
            }
            .show()
    }

    private class Opt(val label: String, val action: () -> Unit)

    private class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    private fun tracks(type: Int): List<TrackChoice> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackChoice>()
        for (gr in p.currentTracks.groups) {
            if (gr.type != type) continue
            for (i in 0 until gr.length) {
                if (!gr.isTrackSupported(i)) continue
                out += TrackChoice(gr, i, trackLabel(gr.getTrackFormat(i), out.size + 1), gr.isTrackSelected(i))
            }
        }
        return out
    }

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).displayLanguage.ifBlank { it } }
        val channels = when (f.channelCount) {
            1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null
        }
        return listOfNotNull(f.label ?: lang ?: "Track $n", channels).joinToString(" · ")
    }

    private fun selected(list: List<TrackChoice>) = list.firstOrNull { it.selected }?.label ?: "Auto"

    private fun isTextOff(): Boolean =
        player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true ||
            tracks(C.TRACK_TYPE_TEXT).none { it.selected }

    private fun pickTrack(type: Int, list: List<TrackChoice>, allowOff: Boolean) {
        val p = player ?: return
        val labels = (if (allowOff) listOf("Off") else emptyList()) + list.map { it.label }
        val sel = if (allowOff && isTextOff()) 0 else list.indexOfFirst { it.selected }.let { if (allowOff) it + 1 else it }.coerceAtLeast(0)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), sel) { d, which ->
                d.dismiss()
                val b = p.trackSelectionParameters.buildUpon()
                if (allowOff && which == 0) {
                    b.setTrackTypeDisabled(type, true)
                } else {
                    val t = list[if (allowOff) which - 1 else which]
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                p.trackSelectionParameters = b.build()
            }
            .show()
    }

    private fun aspectName(a: Int) = when (a) { 1 -> "Zoom to fill"; 2 -> "Stretch"; else -> "Fit" }

    private fun applyAspect() {
        playerView.resizeMode = when (prefs.aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun pickAspect() {
        val labels = arrayOf("Fit (whole picture)", "Zoom to fill (crops edges)", "Stretch")
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Picture")
            .setSingleChoiceItems(labels, prefs.aspect) { d, which ->
                d.dismiss()
                prefs.aspect = which
                applyAspect()
            }
            .show()
    }

    private fun toggleFavourite(g: ChannelGroup) {
        val favs = prefs.favourites.toMutableSet()
        val added = favs.add(g.key)
        if (!added) favs.remove(g.key)
        prefs.favourites = favs
        say(if (added) "Added ${g.name} to favourites" else "Removed ${g.name} from favourites")
        listAdapter.refresh(g.key)
    }

    // ------------------------------------------------------------------ channel list

    private fun showList() {
        val list = keys.mapNotNull { Library.channel(it) }
        if (list.isEmpty()) return
        listAdapter.submit(list)
        findViewById<TextView>(R.id.listTitle).text = "Channels  ·  ${list.size}"
        hideOverlayNow()
        listPanel.visibility = View.VISIBLE
        listPanel.translationX = -Motion.dp(this, 60f)
        listPanel.alpha = 0f
        listPanel.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Motion.settle).start()
        val i = list.indexOfFirst { it.key == group?.key }.coerceAtLeast(0)
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Motion.dp(channelList, 160f))
        channelList.post { channelList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    private fun hideList() {
        if (listPanel.visibility != View.VISIBLE) return
        listPanel.animate().translationX(-Motion.dp(this, 60f)).alpha(0f).setDuration(200)
            .withEndAction { listPanel.visibility = View.GONE }.start()
    }

    private val listOpen get() = listPanel.visibility == View.VISIBLE

    private fun changeChannel(delta: Int) {
        if (keys.size < 2) return
        repeat(keys.size) {
            index = (index + delta + keys.size) % keys.size
            val g = Library.channel(keys[index])
            if (g != null) {
                openChannel(g)
                return
            }
        }
    }

    // ------------------------------------------------------------------ info bar

    private fun updateOverlay() {
        val g = group ?: return
        overlayName.text = g.name
        val bits = ArrayList<String>()
        current?.label?.let { bits += it }
        player?.videoFormat?.let { f -> if (f.height > 0) bits += "${f.height}p" + if (f.frameRate > 0) " ${f.frameRate.toInt()}fps" else "" }
        bits += if (hls) "HLS" else "TS"
        if (g.variants.size > 1) bits += "${g.variants.size} versions"
        overlayQuality.text = bits.joinToString("  ·  ")
        overlayPos.text = if (keys.size > 1) "${index + 1} / ${keys.size}" else ""
        if (g.icon != null) overlayLogo.load(g.icon) else { overlayLogo.dispose(); overlayLogo.setImageDrawable(null) }
    }

    private fun loadGuide(g: ChannelGroup) {
        nowTime.text = ""
        nowTitle.text = ""
        nowDesc.text = ""
        nextText.text = ""
        nowProgress.visibility = View.INVISIBLE
        Epg.fetch(prefs, g) { list ->
            if (group?.key != g.key) return@fetch
            val now = System.currentTimeMillis()
            val cur = list.firstOrNull { it.start <= now && it.end > now }
            val next = list.firstOrNull { it.start >= (cur?.end ?: now) }
            if (cur != null) {
                nowTime.text = "${Epg.time(cur.start)} – ${Epg.time(cur.end)}"
                nowTitle.text = cur.title
                nowDesc.text = cur.description ?: ""
                nowProgress.visibility = View.VISIBLE
                nowProgress.progress = Epg.progress(cur, now)
            } else if (list.isEmpty()) nowTitle.text = "No guide information"
            nextText.text = next?.let { "Next  ${Epg.time(it.start)}  ${it.title}" } ?: ""
        }
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().cancel()
        overlay.alpha = 1f
        handler.postDelayed(hideOverlay, 5000)
    }

    private fun hideOverlayNow() {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().alpha(0f).setDuration(150).start()
    }

    private fun say(text: String) {
        toast.text = text
        handler.removeCallbacks(hideToast)
        toast.animate().cancel()
        toast.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed(hideToast, 3500)
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (listOpen) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                hideList(); return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> {
                    showOptions(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    changeChannel(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    changeChannel(1); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    showList(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                    updateOverlay(); showOverlay(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
