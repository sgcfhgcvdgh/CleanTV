package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView

/**
 * Full-screen player.
 *  OK / Menu  -> choose quality (FHD, HD, SD ... of the same channel)
 *  Up / Down  -> previous / next channel in the list you came from
 *  Left/Right -> show channel info
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_LIST = "list"
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var overlayName: TextView
    private lateinit var overlayQuality: TextView
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView

    private var player: ExoPlayer? = null
    private var keys: List<String> = emptyList()
    private var index = 0
    private var group: ChannelGroup? = null
    private var order: List<Variant> = emptyList()
    private var current: Variant? = null
    private val failed = HashSet<Int>()
    private var reachedReady = false
    private var qualityDialog: AlertDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        api = XtreamApi(prefs.server, prefs.username, prefs.password)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        overlayName = findViewById(R.id.overlayName)
        overlayQuality = findViewById(R.id.overlayQuality)
        loading = findViewById(R.id.loading)
        errorText = findViewById(R.id.errorText)

        playerView.useController = false
        playerView.isFocusable = false

        val key = intent.getStringExtra(EXTRA_KEY)
        keys = intent.getStringArrayListExtra(EXTRA_LIST) ?: arrayListOf()
        val g = key?.let { Library.channel(it) }
        if (g == null) {
            finish()
            return
        }
        index = keys.indexOf(key).coerceAtLeast(0)
        openChannel(g)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initPlayer()
        current?.let { playVariant(it, announce = false) }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        qualityDialog?.dismiss()
        super.onDestroy()
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build()
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                when (state) {
                    Player.STATE_READY -> {
                        reachedReady = true
                        errorText.visibility = View.GONE
                    }
                    // A live stream "ending" means the connection dropped: reconnect.
                    Player.STATE_ENDED -> current?.let { playVariant(it, announce = false) }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) = onStreamFailed(error)
        })
        playerView.player = p
        player = p
    }

    private fun openChannel(g: ChannelGroup) {
        group = g
        failed.clear()
        order = ChannelGrouper.playOrder(g, prefs.defaultQuality)
        current = order.firstOrNull()
        errorText.visibility = View.GONE
        current?.let { if (player != null) playVariant(it, announce = true) }
        updateOverlay()
        showOverlay()
    }

    private fun playVariant(v: Variant, announce: Boolean) {
        current = v
        reachedReady = false
        val hls = prefs.useHls
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
        // It was playing fine and dropped: reconnect to the same quality once.
        if (reachedReady) {
            handler.postDelayed({ if (current == cur) playVariant(cur, announce = false) }, 1000)
            return
        }
        failed += cur.stream.id
        val next = order.firstOrNull { it.stream.id !in failed }
        if (next != null) {
            Toast.makeText(this, "${cur.label} won't load, switching to ${next.label}", Toast.LENGTH_SHORT).show()
            playVariant(next, announce = true)
        } else {
            loading.visibility = View.GONE
            errorText.visibility = View.VISIBLE
            errorText.text = "This channel isn't working right now (${error.errorCodeName}).\n\n" +
                "Press OK to try a quality again, or ▲ ▼ to change channel."
        }
    }

    private fun showQualityPicker() {
        val g = group ?: return
        val variants = g.variants
        val labels = variants.map { v ->
            buildString {
                append(v.label)
                if (v == current) append("   ● playing")
                else if (v.stream.id in failed) append("   (didn't load)")
            }
        }.toTypedArray()
        qualityDialog?.dismiss()
        qualityDialog = AlertDialog.Builder(this)
            .setTitle(if (variants.size > 1) "${g.name} – quality" else "${g.name} – only one version available")
            .setSingleChoiceItems(labels, (current?.let { variants.indexOf(it) } ?: 0).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val v = variants[which]
                failed.remove(v.stream.id)
                errorText.visibility = View.GONE
                playVariant(v, announce = true)
            }
            .show()
    }

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

    private fun updateOverlay() {
        val g = group ?: return
        overlayName.text = g.name
        val q = current?.label ?: ""
        overlayQuality.text = if (g.variants.size > 1) {
            "$q   ·   ${g.variants.size} versions – press OK to switch"
        } else q
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideOverlay)
        overlay.animate().cancel()
        overlay.alpha = 1f
        handler.postDelayed(hideOverlay, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU -> {
                    showQualityPicker(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    changeChannel(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    changeChannel(1); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                    showOverlay(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
