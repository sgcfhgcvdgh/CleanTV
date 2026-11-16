package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Live TV: categories on the left, channels (with what's on) in the middle, a preview on the right.
 *  OK            -> preview that channel; OK again -> full screen
 *  Hold OK       -> pick the channel up; ▲ ▼ move it, OK drops it, Back cancels
 *  ☰ Menu        -> channel options (favourite, move, full screen) or Settings
 *  ◀ on the categories -> back to the menu
 */
class LiveActivity : AppCompatActivity(), LiveEngine.Listener {

    companion object {
        const val FAV = "__fav"
        const val ALL = "__all"
        const val EXTRA_SEARCH = "search"
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var catList: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var firstLoad: ProgressBar
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chanAdapter: ChannelListAdapter

    // Preview
    private lateinit var previewVlc: VLCVideoLayout
    private lateinit var previewExo: PlayerView
    private lateinit var previewEmpty: TextView
    private lateinit var previewLoading: ProgressBar
    private lateinit var previewName: TextView
    private lateinit var previewQuality: TextView
    private lateinit var previewNow: TextView
    private lateinit var previewProgress: ProgressBar
    private lateinit var previewDesc: TextView
    private lateinit var previewNext: TextView
    private var engine: LiveEngine? = null
    private var previewKey: String? = null

    // Moving a channel
    private var picked: String? = null
    private var pickedFrom: List<ChannelGroup> = emptyList()
    /** The hold that picked a channel up is still down: its release mustn't drop it. */
    private var pickHeld = false
    private var swallowCenterUp = false

    /** The channel you were last on, to come back to with ▶. */
    private var lastChannelKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var currentCat: String = ALL
    private var catNames: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = XtreamApi(prefs.server, prefs.username, prefs.password)
        setContentView(R.layout.activity_live)

        catList = findViewById(R.id.categories)
        grid = findViewById(R.id.grid)
        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        firstLoad = findViewById(R.id.firstLoad)
        previewVlc = findViewById(R.id.previewVlc)
        previewExo = findViewById(R.id.previewExo)
        previewEmpty = findViewById(R.id.previewEmpty)
        previewLoading = findViewById(R.id.previewLoading)
        previewName = findViewById(R.id.previewName)
        previewQuality = findViewById(R.id.previewQuality)
        previewNow = findViewById(R.id.previewNow)
        previewProgress = findViewById(R.id.previewProgress)
        previewDesc = findViewById(R.id.previewDesc)
        previewNext = findViewById(R.id.previewNext)
        previewExo.useController = false
        previewExo.isFocusable = false

        catAdapter = CategoryAdapter { item, fromFocus ->
            if (picked != null) return@CategoryAdapter
            if (item.key == currentCat && search.text.isEmpty()) return@CategoryAdapter
            schedule(if (fromFocus) 220L else 0L) {
                search.text.clear()
                showCategory(item.key, resetScroll = true)
            }
        }
        chanAdapter = ChannelListAdapter(
            prefs,
            onClick = { g -> onChannelPressed(g) },
            onLongClick = { g -> pickUp(g) },
            onFocus = { g -> lastChannelKey = g.key },
        )

        catList.layoutManager = LinearLayoutManager(this)
        catList.itemAnimator = null
        catList.adapter = catAdapter

        grid.layoutManager = LinearLayoutManager(this)
        grid.itemAnimator = null
        grid.adapter = chanAdapter

        findViewById<View>(R.id.settings).setOnClickListener { SettingsDialog.show(this) { onSettingsChanged() } }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) schedule(300L) { showSearch(q) }
            }
        })

        val existing = Library.data
        if (existing != null) {
            showLibrary(existing)
            refreshQuietly()
        } else load()
    }

    override fun onStart() {
        super.onStart()
        // Back from full screen: the preview carries on with that channel, and focus is on it.
        val key = previewKey
        if (key != null) {
            Library.channel(key)?.let { startPreview(it) }
            focusChannel(key)
        }
    }

    override fun onStop() {
        releasePreview()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun schedule(delay: Long, block: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { block() }
        pending = r
        if (delay == 0L) handler.post(r) else handler.postDelayed(r, delay)
    }

    /** Every minute the guide's progress bars move on and finished programmes roll over. */
    private val ticker = object : Runnable {
        override fun run() {
            chanAdapter.tick()
            previewKey?.let { k -> Library.channel(k)?.let { showPreviewGuide(it) } }
            handler.postDelayed(this, 60_000)
        }
    }

    // ------------------------------------------------------------------ loading

    /** Hidden categories may have changed: redraw from the loaded list (or reload after a refresh). */
    private fun onSettingsChanged() {
        val lib = Library.data
        if (lib != null) showLibrary(lib) else load()
    }

    private fun load() {
        // A saved list opens instantly; only the very first launch has to wait for the provider.
        val firstEver = !ChannelCache.exists
        if (firstEver) {
            firstLoad.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = "Loading your channels for the first time…"
        }
        Bg.run {
            val result = runCatching { Library.load(api) }
            Bg.ui {
                if (isFinishing || isDestroyed) return@ui
                firstLoad.visibility = View.GONE
                result.onSuccess {
                    showLibrary(it)
                    if (!firstEver) refreshQuietly()
                }.onFailure { e ->
                    status.visibility = View.VISIBLE
                    status.text = XtreamApi.friendlyError(e)
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't load channels")
                        .setMessage(XtreamApi.friendlyError(e))
                        .setPositiveButton("Try again") { _, _ -> load() }
                        .setNegativeButton("Back") { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    /** Fetches a fresh list in the background and updates the screen only if something changed. */
    private fun refreshQuietly() {
        Bg.run {
            val fresh = Library.refreshInBackground(api) ?: return@run
            Bg.ui { if (!isFinishing && !isDestroyed && picked == null) applyRefresh(fresh) }
        }
    }

    private fun applyRefresh(lib: GroupedLibrary) {
        buildCategories(lib, select = currentCat)
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) showSearch(q) else showCategory(currentCat, resetScroll = false)
    }

    private fun showLibrary(lib: GroupedLibrary) {
        status.visibility = View.GONE
        val favCount = lib.channels.count { prefs.favourites.contains(it.key) }
        val start = if (favCount > 0) FAV else null
        val items = buildCategories(lib, select = start)
        val first = start ?: items.getOrNull(2)?.key ?: ALL
        catAdapter.submit(items, first)
        showCategory(first, resetScroll = true)
        catList.post {
            val i = items.indexOfFirst { it.key == first }.coerceAtLeast(0)
            catList.scrollToPosition(i)
            catList.post { catList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
        intent.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }?.let { q ->
            intent.removeExtra(EXTRA_SEARCH)
            search.setText(q)
            showSearch(q)
        }
    }

    private fun buildCategories(lib: GroupedLibrary, select: String?): List<CatItem> {
        val hidden = prefs.hiddenLiveCategories
        val visible = Library.visible(prefs)
        val counts = HashMap<String, Int>()
        for (ch in visible) for (k in ch.categoryKeys) counts[k] = (counts[k] ?: 0) + 1
        val favCount = lib.channels.count { prefs.favourites.contains(it.key) }
        val items = ArrayList<CatItem>()
        items += CatItem(FAV, "★ Favourites", favCount)
        items += CatItem(ALL, "All channels", visible.size)
        for (c in lib.categories) {
            if (c.key in hidden) continue
            val n = counts[c.key] ?: 0
            if (n > 0) items += CatItem(c.key, c.name, n)
        }
        catNames = items.associate { it.key to it.name }
        if (select != null) catAdapter.submit(items, select)
        return items
    }

    /** A category's channels, in your own order where you've arranged them. */
    private fun channelsIn(key: String): List<ChannelGroup> {
        val lib = Library.data ?: return emptyList()
        val favs = prefs.favourites
        val list = when (key) {
            FAV -> lib.channels.filter { favs.contains(it.key) }
            ALL -> Library.visible(prefs)
            else -> lib.channels.filter { key in it.categoryKeys }
        }
        return ChannelOrder.apply(list, prefs.channelOrder(key)) { it.key }
    }

    private fun showCategory(key: String, resetScroll: Boolean) {
        currentCat = key
        if (Library.data == null) return
        val list = channelsIn(key)
        heading.text = "${catNames[key] ?: ""}  ·  ${list.size} channels"
        chanAdapter.submit(list)
        if (resetScroll) grid.scrollToPosition(0)
        if (list.isEmpty() && key == FAV) {
            status.visibility = View.VISIBLE
            status.text = "No favourites yet.\nPress ☰ on any channel to add it here."
        } else {
            status.visibility = View.GONE
        }
    }

    private fun showSearch(q: String) {
        if (Library.data == null) return
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        val list = Library.visible(prefs).filter { ch -> words.all { ch.name.contains(it, ignoreCase = true) } }
        heading.text = "Search “$q”  ·  ${list.size} channels"
        chanAdapter.submit(list)
        grid.scrollToPosition(0)
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = "Nothing matches “$q”"
    }

    private fun focusChannel(key: String) {
        val i = chanAdapter.items.indexOfFirst { it.key == key }
        if (i < 0) return
        (grid.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Motion.dp(grid, 120f))
        grid.post { grid.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    // ------------------------------------------------------------------ preview and full screen

    private fun onChannelPressed(g: ChannelGroup) {
        if (picked != null) return
        if (g.key == previewKey && engine != null) fullScreen(g) else startPreview(g)
    }

    private fun startPreview(g: ChannelGroup) {
        val first = previewKey == null
        previewKey = g.key
        chanAdapter.playingKey = g.key
        if (engine == null) {
            engine = runCatching {
                if (prefs.liveEngine == LiveEngine.VLC) VlcEngine(this, previewVlc, this) else ExoEngine(this, previewExo, this)
            }.getOrElse { ExoEngine(this, previewExo, this) }
            engine?.start()
            engine?.setAspect(0)
        }
        previewEmpty.visibility = View.GONE
        previewLoading.visibility = View.VISIBLE
        val v = ChannelGrouper.playOrder(g, prefs.defaultQuality).firstOrNull() ?: g.variants.firstOrNull() ?: return
        engine?.play(api.streamUrl(v.stream.id, prefs.useHls), prefs.useHls)
        previewName.text = g.name
        previewQuality.text = listOf(v.label, g.badges.takeIf { g.variants.size > 1 }).filterNotNull().joinToString("  ·  ")
        showPreviewGuide(g)
        if (first) Toast.makeText(this, "Press OK again for full screen", Toast.LENGTH_SHORT).show()
    }

    private fun showPreviewGuide(g: ChannelGroup) {
        Epg.fetch(prefs, g) { list ->
            if (previewKey != g.key) return@fetch
            val now = System.currentTimeMillis()
            val cur = list.firstOrNull { it.start <= now && it.end > now }
            val next = list.firstOrNull { it.start >= (cur?.end ?: now) }
            previewNow.text = cur?.let { "${Epg.time(it.start)} – ${Epg.time(it.end)}   ${it.title}" } ?: if (list.isEmpty()) "No guide information" else ""
            previewProgress.visibility = if (cur != null) View.VISIBLE else View.INVISIBLE
            cur?.let { previewProgress.progress = Epg.progress(it, now) }
            previewDesc.text = cur?.description ?: ""
            previewNext.text = next?.let { "Next  ${Epg.time(it.start)}  ${it.title}" } ?: ""
        }
    }

    private fun fullScreen(g: ChannelGroup) {
        // The preview's decoder is handed over to the full-screen player.
        releasePreview()
        Playback.playChannel(this, g.key, chanAdapter.items.map { it.key })
    }

    private fun releasePreview() {
        engine?.release()
        engine = null
    }

    override fun onReady() {
        previewLoading.visibility = View.GONE
    }

    override fun onBuffering() {
        previewLoading.visibility = View.VISIBLE
    }

    override fun onError(behindLive: Boolean) {
        previewLoading.visibility = View.GONE
        previewNow.text = "Couldn't preview this channel. Press OK again to try full screen, which tries every version."
    }

    override fun onEnded() {
        previewKey?.let { k -> Library.channel(k)?.let { startPreview(it) } }
    }

    override fun onTracksChanged() {}

    // ------------------------------------------------------------------ moving channels

    private fun pickUp(g: ChannelGroup) {
        if (search.text.isNotBlank()) {
            Toast.makeText(this, "Clear the search to move channels", Toast.LENGTH_SHORT).show()
            return
        }
        picked = g.key
        pickedFrom = chanAdapter.items
        pickHeld = true
        chanAdapter.pickedKey = g.key
    }

    private fun moveBy(delta: Int) {
        val key = picked ?: return
        val from = chanAdapter.items.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in chanAdapter.items.indices) return
        chanAdapter.move(from, to)
        grid.scrollToPosition(to)
        grid.post { grid.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
    }

    private fun drop() {
        val key = picked ?: return
        prefs.setChannelOrder(currentCat, chanAdapter.items.map { it.key })
        picked = null
        chanAdapter.pickedKey = null
        Toast.makeText(this, "Order saved for ${catNames[currentCat] ?: "this category"}", Toast.LENGTH_SHORT).show()
        focusChannel(key)
    }

    private fun cancelMove() {
        val key = picked ?: return
        picked = null
        chanAdapter.pickedKey = null
        chanAdapter.submit(pickedFrom)
        focusChannel(key)
    }

    // ------------------------------------------------------------------ menus and keys

    private fun focusedChannel(): ChannelGroup? {
        val f = currentFocus ?: return null
        val holder = grid.findContainingViewHolder(f) ?: return null
        return chanAdapter.items.getOrNull(holder.bindingAdapterPosition)
    }

    private fun channelMenu(g: ChannelGroup) {
        val fav = prefs.favourites.contains(g.key)
        val options = arrayOf(
            if (fav) "Remove from favourites" else "Add to favourites",
            "Move this channel",
            "Watch full screen",
            "Settings",
        )
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavourite(g)
                    1 -> { pickUp(g); pickHeld = false }
                    2 -> fullScreen(g)
                    3 -> SettingsDialog.show(this) { onSettingsChanged() }
                }
            }
            .show()
    }

    private fun toggleFavourite(g: ChannelGroup) {
        val favs = prefs.favourites.toMutableSet()
        val added = favs.add(g.key)
        if (!added) favs.remove(g.key)
        prefs.favourites = favs
        Toast.makeText(this, if (added) "Added ${g.name} to favourites" else "Removed ${g.name} from favourites", Toast.LENGTH_SHORT).show()
        catAdapter.updateCount(FAV, favs.size)
        if (currentCat == FAV && search.text.isEmpty()) showCategory(FAV, resetScroll = false) else chanAdapter.refresh(g.key)
    }

    private fun isCenter(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // Moving a channel: ▲ ▼ move it, OK drops it, Back cancels; nothing else.
        if (picked != null) {
            when {
                isCenter(code) -> {
                    if (pickHeld) {
                        // Still the hold that picked it up: let the release through (it doesn't click).
                        if (!down) { pickHeld = false; return super.dispatchKeyEvent(event) }
                        return true
                    }
                    if (down && event.repeatCount == 0) { drop(); swallowCenterUp = true }
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveBy(-1); return true }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveBy(1); return true }
                code == KeyEvent.KEYCODE_BACK -> { if (down) cancelMove(); return true }
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MENU -> return true
            }
        }
        if (swallowCenterUp && isCenter(code) && !down) {
            swallowCenterUp = false
            return true
        }
        if (down) {
            val focus = currentFocus
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (focus != null && isInside(catList, focus) && chanAdapter.itemCount > 0) {
                    // ▶ from the categories goes back to the channel you were on.
                    val key = lastChannelKey?.takeIf { k -> chanAdapter.items.any { it.key == k } }
                    if (key != null) focusChannel(key) else grid.post {
                        val first = (grid.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
                        grid.findViewHolderForAdapterPosition(first)?.itemView?.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    val g = focusedChannel()
                    if (g != null) channelMenu(g) else SettingsDialog.show(this) { onSettingsChanged() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (focus != null && isInside(grid, focus)) {
                    // ◀ from the channels always lands on the category you're in.
                    focusCurrentCategory()
                    return true
                } else if (focus != null && (isInside(catList, focus) || focus === search ||
                        focus.id == R.id.settings)) {
                    // ◀ from the categories goes back to the menu, on Live TV.
                    MainActivity.openRailOn = "live"
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Focus the category you're in (or the search box while searching), scrolling to it if needed. */
    private fun focusCurrentCategory() {
        if (search.text.isNotBlank()) {
            search.requestFocus(); return
        }
        val i = catAdapter.items.indexOfFirst { it.key == currentCat }
        if (i < 0) {
            catList.requestFocus(); return
        }
        val holder = catList.findViewHolderForAdapterPosition(i)
        if (holder != null) holder.itemView.requestFocus()
        else {
            (catList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Motion.dp(catList, 120f))
            catList.post { catList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
    }

    private fun isInside(parent: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }
}
