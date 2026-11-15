package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pick a sport, see every game that's on now and coming up, and press OK on one to watch it
 * on the channel in your list that's showing it.
 */
class SportsActivity : AppCompatActivity(), CardHost {

    private lateinit var prefs: Prefs
    private lateinit var chips: RecyclerView
    private lateinit var rowsView: RecyclerView
    private lateinit var rowsAdapter: RowsAdapter
    private lateinit var status: TextView
    private lateinit var updated: TextView
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var heroHome: ImageView
    private lateinit var heroAway: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var sport: Sport = SportsCatalog.all
    private var generation = 0
    private var heroGameId: String? = null

    private val refresher = object : Runnable {
        override fun run() {
            load(sport, quiet = true)
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_sports)
        chips = findViewById(R.id.sportChips)
        rowsView = findViewById(R.id.rows)
        status = findViewById(R.id.status)
        updated = findViewById(R.id.updated)
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroDetail = findViewById(R.id.heroDetail)
        heroHome = findViewById(R.id.heroHome)
        heroAway = findViewById(R.id.heroAway)

        sport = SportsCatalog.sport(prefs.lastSport.ifBlank { "all" })

        chips.layoutManager = KeylineLayoutManager(this, RecyclerView.HORIZONTAL)
        chips.itemAnimator = null
        chips.adapter = ChipAdapter()

        rowsAdapter = RowsAdapter(this)
        rowsView.layoutManager = KeylineLayoutManager(this, RecyclerView.VERTICAL)
        rowsView.itemAnimator = null
        rowsView.adapter = rowsAdapter

        findViewById<View>(R.id.root).viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> dimRows(now) }

        // Games show straight away; once your channels have loaded, the ones you can watch get marked.
        load(sport, quiet = false)
        heroKicker.text = sport.name
        heroTitle.text = "Loading games…"
        chips.post {
            val i = SportsCatalog.tabs.indexOf(sport)
            chips.scrollToPosition(i)
            chips.post { chips.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresher, 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun isIn(parent: View, v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    private fun dimRows(focus: View?) = Rows.dim(rowsView, focus?.takeIf { isIn(rowsView, it) }, idle = 1f)

    // ------------------------------------------------------------------ data

    private fun selectSport(s: Sport) {
        if (s.id == sport.id) return
        sport = s
        prefs.lastSport = s.id
        // Mark the chosen chip without rebinding (rebinding would knock focus off the chips).
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i)
            val pos = chips.getChildAdapterPosition(c)
            c.isSelected = SportsCatalog.tabs.getOrNull(pos)?.id == s.id
        }
        rowsAdapter.forgetScroll()
        load(s, quiet = false)
    }

    private fun load(s: Sport, quiet: Boolean) {
        val gen = ++generation
        val extra = s.id != "all" // "All sports" asks for each league's current round only
        val n = s.leagues.size
        val cached = arrayOfNulls<List<Game>>(n)
        val fresh = arrayOfNulls<List<Game>>(n)
        val left = AtomicInteger(n)
        val firstShown = java.util.concurrent.atomic.AtomicBoolean(quiet)

        /** Rows from whatever has arrived: fresh answers where we have them, last time's otherwise. */
        fun current(): List<List<Game>> = (0 until n).map { fresh[it] ?: cached[it] ?: emptyList() }

        fun showNow(matches: Map<String, List<GameMatcher.Candidate>>) {
            val built = buildRows(s, current(), matches)
            Bg.ui {
                if (gen != generation) return@ui
                // The first time this sport is shown the list starts at the top; after that it just fills in.
                show(s, built.first, built.second, quiet = firstShown.getAndSet(true))
            }
        }

        if (!quiet) {
            status.visibility = View.VISIBLE
            status.text = "Loading ${s.name}…"
            // Instantly: the games saved last time (even before a restart), with the last channel matches.
            SportsFeed.pool.execute {
                for (i in 0 until n) cached[i] = runCatching { SportsFeed.games(s.leagues[i], extra, offline = true) }.getOrNull()
                if (gen == generation && cached.any { !it.isNullOrEmpty() } && fresh.all { it == null }) showNow(SportsGuide.matches)
            }
        }

        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        s.leagues.forEachIndexed { i, league ->
            SportsFeed.pool.execute {
                fresh[i] = runCatching { SportsFeed.games(league, extra) }.getOrDefault(emptyList())
                if (gen != generation) return@execute
                if (left.decrementAndGet() > 0) {
                    // Each league slots in as it arrives (at most a few redraws a second).
                    if (pending.compareAndSet(false, true)) handler.postDelayed({
                        pending.set(false)
                        if (gen == generation) SportsFeed.pool.execute { showNow(SportsGuide.matches) }
                    }, 300)
                    return@execute
                }
                showNow(SportsGuide.matches)
                // Then which games are on your channels: TV listings, the guide and event channels.
                val found = SportsGuide.matchAll(prefs, current().flatten())
                if (gen == generation) showNow(found)
            }
        }
    }

    private fun buildRows(s: Sport, perLeague: List<List<Game>>, found: Map<String, List<GameMatcher.Candidate>>): Pair<List<Row>, Int> {
        fun card(g: Game) = Card.GameCard(g, SportsGuide.watchable(found[g.id]))
        val all = perLeague.flatten()
        val rows = ArrayList<Row>()
        val live = all.filter { it.state == GameState.LIVE }
        if (live.isNotEmpty()) rows += Row("live", "Live now", live.map { card(it) }.sortedByDescending { it.watchable }, RowStyle.GAME)
        s.leagues.forEachIndexed { i, league ->
            val upcoming = perLeague[i].filter { it.state == GameState.UPCOMING }.take(30)
            if (upcoming.isNotEmpty()) rows += Row("up:${league.path}", "${league.name}  ·  coming up", upcoming.map { card(it) }, RowStyle.GAME)
        }
        val finished = all.filter { it.state == GameState.FINISHED }.sortedByDescending { it.startMs }.take(30)
        if (finished.isNotEmpty()) rows += Row("results", "Results", finished.map { card(it) }, RowStyle.GAME)
        return rows to live.size
    }

    private fun show(s: Sport, rows: List<Row>, liveCount: Int, quiet: Boolean) {
        if (isFinishing || isDestroyed) return
        updated.text = "Updated " + SimpleDateFormat("HH:mm", Locale.UK).format(Date()) +
            "  ·  scores refresh every minute" + if (liveCount > 0) "  ·  $liveCount live" else ""

        val sameShape = rowsAdapter.rows.map { it.id to it.cards.size } == rows.map { it.id to it.cards.size }
        if (quiet && sameShape) {
            // Scores changed but nothing moved: refresh in place so focus stays put.
            rows.forEach { rowsAdapter.update(it) }
        } else {
            // Stable ids keep focus on the same card while rows are added around it.
            rowsAdapter.submit(rows)
            if (!quiet) rowsView.scrollToPosition(0)
        }
        rowsView.animate().alpha(1f).setDuration(220).start()
        status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        status.text = "No ${s.name} games on now or in the next few days."
        if (heroGameId == null || !quiet) {
            val first = rows.firstOrNull()?.cards?.firstOrNull() as? Card.GameCard
            if (first != null) showHero(first.game) else {
                heroKicker.text = s.name
                heroTitle.text = "Nothing on right now"
                heroDetail.text = ""
                heroHome.visibility = View.GONE; heroAway.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ hero

    private fun showHero(g: Game) {
        heroGameId = g.id
        Motion.swapText(heroKicker, (if (g.state == GameState.LIVE) "● Live  ·  " else "") + g.league.name)
        val title = if (g.headToHead && g.state != GameState.UPCOMING)
            "${g.home!!.short}  ${g.home!!.score ?: "-"} – ${g.away!!.score ?: "-"}  ${g.away!!.short}"
        else if (g.headToHead) "${g.home!!.short} v ${g.away!!.short}" else g.eventName
        Motion.swapText(heroTitle, title)
        val bits = arrayListOf(g.detail)
        if (g.broadcasts.isNotEmpty()) bits += g.broadcasts.take(4).joinToString(", ")
        Motion.swapText(heroDetail, bits.joinToString("   ·   "))
        fun logo(iv: ImageView, url: String?) {
            if (url == null) { iv.dispose(); iv.visibility = View.GONE; return }
            iv.visibility = View.VISIBLE
            iv.load(url) { crossfade(300) }
        }
        logo(heroHome, g.home?.logo)
        logo(heroAway, g.away?.logo)
    }

    override fun onCardFocused(card: Card) {
        if (card is Card.GameCard) showHero(card.game)
    }

    override fun onCardClicked(card: Card) {
        if (card is Card.GameCard) Playback.watchGame(this, card.game)
    }

    // ------------------------------------------------------------------ sport chips

    private inner class ChipAdapter : RecyclerView.Adapter<ChipAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        private val pending = Runnable { pendingSport?.let { selectSport(it) } }
        private var pendingSport: Sport? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sport_chip, parent, false))

        override fun getItemCount() = SportsCatalog.tabs.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = SportsCatalog.tabs[position]
            val tv = h.itemView as TextView
            tv.text = s.name
            tv.isSelected = s.id == sport.id
            tv.setOnClickListener { handler.removeCallbacks(pending); selectSport(s) }
            tv.setOnFocusChangeListener { v, has ->
                Motion.focus(v, has, 1.08f)
                if (has) {
                    // Resting on a sport for a moment loads it; flicking past doesn't.
                    pendingSport = s
                    handler.removeCallbacks(pending)
                    handler.postDelayed(pending, 450)
                }
            }
        }
    }
}
