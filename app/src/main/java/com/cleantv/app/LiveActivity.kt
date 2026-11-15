package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Live TV: categories on the left, one tile per channel (qualities merged) on the right. */
class LiveActivity : AppCompatActivity() {

    companion object {
        const val FAV = "__fav"
        const val ALL = "__all"
        const val EXTRA_SEARCH = "search"
    }

    private lateinit var prefs: Prefs
    private lateinit var catList: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chanAdapter: ChannelListAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var currentCat: String = ALL
    private var catNames: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_live)

        catList = findViewById(R.id.categories)
        grid = findViewById(R.id.grid)
        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)

        catAdapter = CategoryAdapter { item, fromFocus ->
            schedule(if (fromFocus) 220L else 0L) {
                search.text.clear()
                showCategory(item.key)
            }
        }
        chanAdapter = ChannelListAdapter(
            prefs,
            onClick = { g -> Playback.playChannel(this, g.key, chanAdapter.items.map { it.key }) },
            onLongClick = { toggleFavourite(it) },
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
        if (existing != null) showLibrary(existing) else load()
    }

    private fun schedule(delay: Long, block: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { block() }
        pending = r
        if (delay == 0L) handler.post(r) else handler.postDelayed(r, delay)
    }

    /** Hidden categories may have changed: redraw from the loaded list (or reload after a refresh). */
    private fun onSettingsChanged() {
        val lib = Library.data
        if (lib != null) showLibrary(lib) else load()
    }

    /** Every minute the guide's progress bars move on and finished programmes roll over. */
    private val ticker = object : Runnable {
        override fun run() {
            chanAdapter.tick()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(ticker, 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = "Loading channels…"
        heading.text = ""
        Bg.run {
            val result = runCatching { Library.load(XtreamApi(prefs.server, prefs.username, prefs.password)) }
            Bg.ui {
                if (isFinishing || isDestroyed) return@ui
                result.onSuccess { showLibrary(it) }.onFailure { e ->
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

    private fun showLibrary(lib: GroupedLibrary) {
        status.visibility = View.GONE
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
        val start = if (favCount > 0) FAV else items.getOrNull(2)?.key ?: ALL
        catNames = items.associate { it.key to it.name }
        catAdapter.submit(items, start)
        showCategory(start)
        catList.post {
            val i = items.indexOfFirst { it.key == start }.coerceAtLeast(0)
            catList.scrollToPosition(i)
            catList.post { catList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
        intent.getStringExtra(EXTRA_SEARCH)?.takeIf { it.isNotBlank() }?.let { q ->
            intent.removeExtra(EXTRA_SEARCH)
            search.setText(q)
            showSearch(q)
        }
    }

    private fun showCategory(key: String) {
        currentCat = key
        val lib = Library.data ?: return
        val favs = prefs.favourites
        val list = when (key) {
            FAV -> lib.channels.filter { favs.contains(it.key) }
            ALL -> Library.visible(prefs)
            else -> lib.channels.filter { key in it.categoryKeys }
        }
        heading.text = "${catNames[key] ?: ""}  ·  ${list.size} channels"
        chanAdapter.submit(list)
        grid.scrollToPosition(0)
        if (list.isEmpty() && key == FAV) {
            status.visibility = View.VISIBLE
            status.text = "No favourites yet.\nHold OK on any channel to add it here."
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

    private fun toggleFavourite(g: ChannelGroup) {
        val favs = prefs.favourites.toMutableSet()
        val added = favs.add(g.key)
        if (!added) favs.remove(g.key)
        prefs.favourites = favs
        Toast.makeText(this, if (added) "Added ${g.name} to favourites" else "Removed ${g.name} from favourites", Toast.LENGTH_SHORT).show()
        catAdapter.updateCount(FAV, favs.size)
        if (currentCat == FAV && search.text.isEmpty()) showCategory(FAV) else chanAdapter.refresh(g.key)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            SettingsDialog.show(this) { onSettingsChanged() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
