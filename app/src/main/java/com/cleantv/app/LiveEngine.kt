package com.cleantv.app

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import java.util.concurrent.TimeUnit

/** An audio or subtitle track a player engine offers. */
class EngineTrack(val key: Any, val label: String, val selected: Boolean)

/**
 * The part of the live player that actually decodes video. Two engines: VLC (default, very
 * forgiving with IPTV streams) and ExoPlayer.
 */
interface LiveEngine {
    interface Listener {
        fun onReady()
        fun onBuffering()
        fun onError(behindLive: Boolean)
        fun onEnded()
        fun onTracksChanged()
    }

    val name: String
    fun start()
    fun play(url: String, hls: Boolean)
    fun pauseOutput()
    fun release()
    val isReady: Boolean
    val isBuffering: Boolean
    /** Pictures shown so far for this stream, or -1 if the engine can't say. */
    fun framesShown(): Int
    fun positionMs(): Long
    fun hasVideo(): Boolean
    fun videoInfo(): String?
    fun audioTracks(): List<EngineTrack>
    fun selectAudio(t: EngineTrack)
    fun subtitleTracks(): List<EngineTrack>
    fun selectSubtitle(t: EngineTrack?)
    fun subtitlesOff(): Boolean
    fun setAspect(mode: Int)
    fun jumpToLive()

    companion object {
        const val VLC = 0
        const val EXO = 1

        fun languageName(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).displayLanguage.ifBlank { it } }
    }
}

// ======================================================================
// VLC
// ======================================================================

class VlcEngine(context: Context, private val layout: VLCVideoLayout, private val listener: LiveEngine.Listener) : LiveEngine {

    override val name = "VLC"

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--network-caching=3000",
            "--live-caching=3000",
            "--http-reconnect",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1",
            "--no-sub-autodetect-file",
        )
    )
    private val player = MediaPlayer(libVlc)
    private var media: Media? = null
    private var ready = false
    private var buffering = false
    private var attached = false

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing, MediaPlayer.Event.Vout -> {
                    ready = true; buffering = false; listener.onReady()
                }
                MediaPlayer.Event.Buffering -> {
                    val b = e.buffering < 100f
                    if (b != buffering) {
                        buffering = b
                        if (b) listener.onBuffering() else if (ready) listener.onReady()
                    }
                }
                MediaPlayer.Event.EndReached -> listener.onEnded()
                MediaPlayer.Event.EncounteredError -> listener.onError(behindLive = false)
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> listener.onTracksChanged()
            }
        }
    }

    override fun start() {
        if (!attached) {
            player.attachViews(layout, null, true, false)
            attached = true
        }
        layout.visibility = View.VISIBLE
    }

    override fun play(url: String, hls: Boolean) {
        ready = false
        buffering = true
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=3000")
        m.addOption(":http-user-agent=" + XtreamApi.USER_AGENT)
        player.media = m
        media?.release()
        media = m
        player.play()
        listener.onBuffering()
    }

    override fun pauseOutput() {
        player.stop()
        if (attached) {
            player.detachViews()
            attached = false
        }
    }

    override fun release() {
        runCatching { player.stop() }
        if (attached) runCatching { player.detachViews() }
        attached = false
        player.release()
        media?.release()
        media = null
        libVlc.release()
        layout.visibility = View.GONE
    }

    override val isReady get() = ready && !buffering
    override val isBuffering get() = buffering

    override fun framesShown(): Int = runCatching { media?.stats?.displayedPictures }.getOrNull() ?: -1
    override fun positionMs(): Long = player.time
    override fun hasVideo(): Boolean = runCatching { player.currentVideoTrack != null }.getOrDefault(false)

    override fun videoInfo(): String? = runCatching {
        player.currentVideoTrack?.let { v ->
            val fps = if (v.frameRateDen > 0) v.frameRateNum / v.frameRateDen else 0
            "${v.height}p" + if (fps > 0) " ${fps}fps" else ""
        }
    }.getOrNull()

    override fun audioTracks(): List<EngineTrack> {
        val cur = player.audioTrack
        return (player.audioTracks ?: emptyArray()).filter { it.id >= 0 }
            .map { EngineTrack(it.id, it.name ?: "Track ${it.id}", it.id == cur) }
    }

    override fun selectAudio(t: EngineTrack) {
        player.setAudioTrack(t.key as Int)
    }

    override fun subtitleTracks(): List<EngineTrack> {
        val cur = player.spuTrack
        return (player.spuTracks ?: emptyArray()).filter { it.id >= 0 }
            .map { EngineTrack(it.id, it.name ?: "Subtitles ${it.id}", it.id == cur) }
    }

    override fun selectSubtitle(t: EngineTrack?) {
        player.setSpuTrack((t?.key as? Int) ?: -1)
    }

    override fun subtitlesOff(): Boolean = player.spuTrack < 0

    override fun setAspect(mode: Int) {
        player.setVideoScale(
            when (mode) {
                1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> MediaPlayer.ScaleType.SURFACE_FILL
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
        )
    }

    override fun jumpToLive() {
        media?.let { player.media = it; player.play() }
    }
}

// ======================================================================
// ExoPlayer
// ======================================================================

@OptIn(UnstableApi::class)
class ExoEngine(context: Context, private val view: PlayerView, private val listener: LiveEngine.Listener) : LiveEngine {

    override val name = "ExoPlayer"

    private val player: ExoPlayer

    init {
        // OkHttp keeps connections alive and copes with flaky IPTV servers better than the default.
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(XtreamApi.USER_AGENT)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors)
        // Live TV: start quickly, but keep a deeper cushion to ride out a wobbly connection.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener.onBuffering()
                    Player.STATE_READY -> listener.onReady()
                    Player.STATE_ENDED -> listener.onEnded()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) =
                listener.onError(error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)

            override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged()
        })
        view.player = player
    }

    override fun start() {
        view.visibility = View.VISIBLE
    }

    override fun play(url: String, hls: Boolean) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    override fun pauseOutput() = player.stop()

    override fun release() {
        view.player = null
        player.release()
        view.visibility = View.GONE
    }

    override val isReady get() = player.playbackState == Player.STATE_READY && player.playWhenReady
    override val isBuffering get() = player.playbackState == Player.STATE_BUFFERING

    override fun framesShown(): Int {
        val c = player.videoDecoderCounters ?: return -1
        c.ensureUpdated()
        return c.renderedOutputBufferCount
    }

    override fun positionMs(): Long = player.currentPosition
    override fun hasVideo(): Boolean = player.videoFormat != null

    override fun videoInfo(): String? = player.videoFormat?.let { f ->
        if (f.height <= 0) null else "${f.height}p" + if (f.frameRate > 0) " ${f.frameRate.toInt()}fps" else ""
    }

    private fun tracks(type: Int): List<EngineTrack> {
        val out = ArrayList<EngineTrack>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += EngineTrack(g to i, label(g.getTrackFormat(i), out.size + 1), g.isTrackSelected(i))
            }
        }
        return out
    }

    private fun label(f: Format, n: Int): String {
        val channels = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        return listOfNotNull(f.label ?: LiveEngine.languageName(f.language) ?: "Track $n", channels).joinToString(" · ")
    }

    @Suppress("UNCHECKED_CAST")
    private fun select(type: Int, t: EngineTrack?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (t == null) b.setTrackTypeDisabled(type, true)
        else {
            val (g, i) = t.key as Pair<Tracks.Group, Int>
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = b.build()
    }

    override fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO)
    override fun selectAudio(t: EngineTrack) = select(C.TRACK_TYPE_AUDIO, t)
    override fun subtitleTracks() = tracks(C.TRACK_TYPE_TEXT)
    override fun selectSubtitle(t: EngineTrack?) = select(C.TRACK_TYPE_TEXT, t)
    override fun subtitlesOff(): Boolean =
        player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || subtitleTracks().none { it.selected }

    override fun setAspect(mode: Int) {
        view.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun jumpToLive() {
        player.seekToDefaultPosition()
        player.prepare()
    }
}
