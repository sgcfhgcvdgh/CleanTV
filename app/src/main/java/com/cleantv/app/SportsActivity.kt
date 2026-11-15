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
    private var sport: Sport = SportsCatalog.sports.first()
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

        sport = SportsCatalog.sport(prefs.lastSport.ifBlank { "football" })

        chips.layoutManager = KeylineLayoutManager(this, RecyclerView.HORIZONTAL)
        chips.itemAnimator = null
        chips.adapter = ChipAdapter()

        rowsAdapter = RowsAdapter(this)
        rowsView.layoutManager = KeylineLayoutManager(this, RecyclerView.VERTICAL)
        rowsView.itemAnimator = null
        rowsView.adapter = rowsAdapter

        findViewById<View>(R.id.root).viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> dimRows(now) }

        // Channels are needed to know which games you can watch.
        Playback.withChannels(this) { load(sport, quiet = false) }
        heroKicker.text = sport.name
        heroTitle.text = "Loading games…"
        chips.post {
            val i = SportsCatalog.sports.indexOf(sport)
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

    private fun dimRows(focus: View?) {
        val inRows = isIn(rowsView, focus)
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val target = if (!inRows) 1f else if (isIn(c, focus)) 1f else 0.5f
            if (c.alpha != target) c.animate().alpha(target).setDuration(220).start()
        }
    }

    // ------------------------------------------------------------------ data

    private fun selectSport(s: Sport) {
        if (s.id == sport.id) return
        sport = s
        prefs.lastSport = s.id
        // Mark the chosen chip without rebinding (rebinding would knock focus off the chips).
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i)
            val pos = chips.getChildAdapterPosition(c)
            c.isSelected = SportsCatalog.sports.getOrNull(pos)?.id == s.id
        }
        rowsAdapter.forgetScroll()
        load(s, quiet = false)
    }

    private fun load(s: Sport, quiet: Boolean) {
        val gen = ++generation
        if (!quiet) {
            status.visibility = View.VISIBLE
            status.text = "Loading ${s.name}…"
            rowsView.animate().alpha(0.3f).setDuration(150).start()
        }
        val results = arrayOfNulls<List<Game>>(s.leagues.size)
        val left = AtomicInteger(s.leagues.size)
        s.leagues.forEachIndexed { i, league ->
            Bg.run {
                results[i] = runCatching { SportsFeed.games(league) }.getOrDefault(emptyList())
                if (left.decrementAndGet() == 0) Bg.ui { if (gen == generation) show(s, results.map { it ?: emptyList() }, quiet) }
            }
        }
    }

    private fun show(s: Sport, perLeague: List<List<Game>>, quiet: Boolean) {
        if (isFinishing) return
        val channels = Library.data?.channels?.map { it.key to it.name }
        fun card(g: Game): Card.GameCard {
            val best = channels?.let { GameMatcher.find(g, it).firstOrNull()?.score } ?: 0
            return Card.GameCard(g, best >= 50)
        }
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

        updated.text = "Updated " + SimpleDateFormat("HH:mm", Locale.UK).format(Date()) +
            "  ·  scores refresh every minute" + if (live.isNotEmpty()) "  ·  ${live.size} live" else ""

        val sameShape = rowsAdapter.rows.map { it.id to it.cards.size } == rows.map { it.id to it.cards.size }
        if (quiet && sameShape) {
            // Scores changed but nothing moved: refresh in place so focus stays put.
            rows.forEach { rowsAdapter.update(it) }
        } else if (!quiet || !isIn(rowsView, currentFocus)) {
            rowsAdapter.submit(rows)
            rowsView.scrollToPosition(0)
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

        override fun getItemCount() = SportsCatalog.sports.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = SportsCatalog.sports[position]
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
