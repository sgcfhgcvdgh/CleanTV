package com.cleantv.app

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home, Movies, TV Shows and My List: a billboard for the title in focus over rows of cards,
 * with the menu rail on the left.
 */
class MainActivity : AppCompatActivity(), CardHost {

    private enum class Section { HOME, MOVIES, SHOWS, MYLIST }

    private data class Featured(val title: Title, val kicker: String, val match: Int)

    private class NavItem(val id: String, val label: String, val icon: Int) {
        lateinit var view: View
    }

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var backdrops: Array<ImageView>
    private var front = 0
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMatch: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroScores: LinearLayout
    private lateinit var heroPlot: TextView
    private lateinit var heroButtons: View
    private lateinit var heroPlay: View
    private lateinit var heroPlayText: TextView
    private lateinit var rowsView: RecyclerView
    private lateinit var rowsAdapter: RowsAdapter
    private lateinit var status: TextView
    private lateinit var rail: LinearLayout
    private lateinit var railDim: View
    private lateinit var intro: View

    private val handler = Handler(Looper.getMainLooper())
    private var section = Section.HOME
    private var featured: Featured? = null
    private var heroShowing: String? = null
    private var currentBackdrop: String? = null
    private var railOpen = false
    /** The menu only takes focus when you ask for it (◀ at the left edge, or Back). */
    private var railAllowed = false
    private var railAnim: ValueAnimator? = null
    private var lastContentFocus: View? = null
    private var liveRow: Row? = null
    private var loading = false
    private var firstRender = true
    private val nav = listOf(
        NavItem("search", "Search", R.drawable.ic_search),
        NavItem("home", "Home", R.drawable.ic_home),
        NavItem("movies", "Movies", R.drawable.ic_movie),
        NavItem("shows", "TV Shows", R.drawable.ic_shows),
        NavItem("sports", "Sports", R.drawable.ic_trophy),
        NavItem("live", "Live TV", R.drawable.ic_live),
        NavItem("mylist", "My List", R.drawable.ic_list),
        NavItem("settings", "Settings", R.drawable.ic_tune),
    )

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        backdrops = arrayOf(findViewById(R.id.backdropA), findViewById(R.id.backdropB))
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroMatch = findViewById(R.id.heroMatch)
        heroMeta = findViewById(R.id.heroMeta)
        heroScores = findViewById(R.id.heroScores)
        heroPlot = findViewById(R.id.heroPlot)
        heroButtons = findViewById(R.id.heroButtons)
        heroPlay = findViewById(R.id.heroPlay)
        heroPlayText = findViewById(R.id.heroPlayText)
        rowsView = findViewById(R.id.rows)
        status = findViewById(R.id.status)
        rail = findViewById(R.id.rail)
        railDim = findViewById(R.id.railDim)
        intro = findViewById(R.id.intro)

        rowsAdapter = RowsAdapter(this)
        rowsView.layoutManager = KeylineLayoutManager(this, RecyclerView.VERTICAL)
        rowsView.adapter = rowsAdapter
        rowsView.itemAnimator = null
        rowsView.setItemViewCacheSize(6)

        heroPlay.setOnClickListener { featured?.let { play(it.title) } }
        findViewById<View>(R.id.heroInfo).setOnClickListener { featured?.let { Playback.openDetails(this, it.title) } }
        listOf(heroPlay, findViewById<View>(R.id.heroInfo)).forEach { b ->
            b.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.06f) }
        }

        buildRail()
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }

        val needsLoad = !Catalog.moviesLoaded && !Catalog.showsLoaded
        if (needsLoad) playIntro()
        load()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a film or episode: Continue Watching and picks may have changed.
        if (!firstRender && !loading) render(keepFocus = true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ intro

    private fun playIntro() {
        intro.visibility = View.VISIBLE
        val mark = findViewById<View>(R.id.introMark)
        val bar = findViewById<View>(R.id.introBar)
        mark.alpha = 0f
        mark.scaleX = 1.35f; mark.scaleY = 1.35f
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(Motion.settle).start()
        bar.scaleX = 0f
        bar.pivotX = 0f
        bar.animate().scaleX(1f).setStartDelay(350).setDuration(700).setInterpolator(Motion.settle).start()
        findViewById<View>(R.id.introStatus).animate().alpha(1f).setStartDelay(1400).setDuration(400).start()
        findViewById<TextView>(R.id.introStatus).text = "Loading your films and shows…"
    }

    private fun endIntro() {
        if (intro.visibility != View.VISIBLE) return
        val content = findViewById<View>(R.id.content)
        content.scaleX = 1.06f; content.scaleY = 1.06f
        content.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(Motion.settle).start()
        intro.animate().alpha(0f).setStartDelay(150).setDuration(600).withEndAction { intro.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ rail

    private fun buildRail() {
        val inflater = LayoutInflater.from(this)
        for (item in nav) {
            val v = inflater.inflate(R.layout.item_nav, rail, false)
            v.findViewById<ImageView>(R.id.navIcon).setImageResource(item.icon)
            v.findViewById<TextView>(R.id.navLabel).text = item.label
            v.contentDescription = item.label
            v.setOnClickListener { onNav(item.id) }
            item.view = v
            rail.addView(v)
        }
        markSection()
    }

    private fun markSection() {
        val id = when (section) {
            Section.HOME -> "home"; Section.MOVIES -> "movies"; Section.SHOWS -> "shows"; Section.MYLIST -> "mylist"
        }
        nav.forEach { it.view.isSelected = it.id == id }
    }

    private fun onNav(id: String) {
        when (id) {
            "search" -> startActivity(Intent(this, SearchActivity::class.java))
            "sports" -> startActivity(Intent(this, SportsActivity::class.java))
            "live" -> startActivity(Intent(this, LiveActivity::class.java))
            "settings" -> SettingsDialog.show(this) { reload() }
            "home" -> switchSection(Section.HOME)
            "movies" -> switchSection(Section.MOVIES)
            "shows" -> switchSection(Section.SHOWS)
            "mylist" -> switchSection(Section.MYLIST)
        }
    }

    private fun setRailOpen(open: Boolean) {
        if (railOpen == open) return
        railOpen = open
        val from = rail.layoutParams.width
        val to = resources.getDimensionPixelSize(if (open) R.dimen.rail_open else R.dimen.rail_closed)
        railAnim?.cancel()
        railAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = Motion.settle
            addUpdateListener {
                rail.layoutParams.width = it.animatedValue as Int
                rail.requestLayout()
            }
            start()
        }
        rail.setBackgroundResource(if (open) R.drawable.bg_rail_open else R.drawable.bg_rail)
        railDim.animate().alpha(if (open) 1f else 0f).setDuration(280).start()
        nav.forEachIndexed { i, item ->
            item.view.findViewById<View>(R.id.navLabel).animate()
                .alpha(if (open) 1f else 0f).setStartDelay(if (open) 40L + i * 18L else 0L)
                .setDuration(200).start()
        }
    }

    private fun isIn(parent: View, v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    private fun onFocusMoved(now: View?) {
        if (now == null) return
        val inRail = isIn(rail, now)
        if (inRail && !railAllowed) {
            // Focus drifted into the menu on its own (first load, a list refresh): send it back.
            root.post { contentFocusTarget().requestFocus() }
            return
        }
        setRailOpen(inRail)
        if (inRail) return
        railAllowed = false
        lastContentFocus = now
        val inHero = isIn(heroButtons, now)
        heroButtons.animate().alpha(if (inHero) 1f else 0f).setDuration(220).start()
        if (inHero) featured?.let { showHero(it.title, it.kicker, it.match) }
        dimRows(if (inHero) null else now)
    }

    private fun contentFocusTarget(): View =
        lastContentFocus?.takeIf { it.isAttachedToWindow && it.isShown && !isIn(rail, it) }
            ?: heroPlay.takeIf { featured != null && it.isShown }
            ?: firstRowCard() ?: heroPlay

    private fun firstRowCard(): View? {
        val row = rowsView.findViewHolderForAdapterPosition(0) as? RowsAdapter.VH ?: return null
        val lm = row.list.layoutManager as? KeylineLayoutManager ?: return null
        val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: 0
        return row.list.findViewHolderForAdapterPosition(pos)?.itemView
    }

    /** The row you're on stays bright, the others dim slightly. */
    private fun dimRows(focus: View?) = Rows.dim(rowsView, focus, idle = 0.9f)

    // ------------------------------------------------------------------ loading

    private fun reload() {
        rowsAdapter.forgetScroll()
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        val api = XtreamApi(prefs.server, prefs.username, prefs.password)
        val remaining = AtomicInteger(2)
        var movieError: Throwable? = null
        var showError: Throwable? = null
        fun done() {
            if (remaining.decrementAndGet() > 0) return
            Bg.ui {
                loading = false
                if (isFinishing || isDestroyed) return@ui
                if (Catalog.movies.isEmpty() && Catalog.shows.isEmpty()) {
                    val e = movieError ?: showError
                    endIntro()
                    status.visibility = View.VISIBLE
                    status.text = e?.let { XtreamApi.friendlyError(it) } ?: "Your account has no films or shows"
                    if (e != null) AlertDialog.Builder(this)
                        .setTitle("Couldn't load your library")
                        .setMessage(XtreamApi.friendlyError(e))
                        .setPositiveButton("Try again") { _, _ -> load() }
                        .setNeutralButton("Live TV") { _, _ -> startActivity(Intent(this, LiveActivity::class.java)) }
                        .setNegativeButton("Change login") { _, _ ->
                            startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_RELOGIN, true))
                            finish()
                        }
                        .show()
                    return@ui
                }
                status.visibility = View.GONE
                render(keepFocus = false)
                loadLiveSports()
                loadChannelsQuietly()
            }
        }
        Bg.run {
            if (!Catalog.moviesLoaded) runCatching { Catalog.loadMovies(api) }.onFailure { movieError = it }
            done()
        }
        Bg.run {
            if (!Catalog.showsLoaded) runCatching { Catalog.loadShows(api) }.onFailure { showError = it }
            done()
        }
    }

    /** Channels load in the background for the Favourites row and for matching live games. */
    private fun loadChannelsQuietly() {
        if (Library.data != null) return
        Bg.run {
            runCatching { Library.load(XtreamApi(prefs.server, prefs.username, prefs.password)) }
            Bg.ui {
                if (isFinishing) return@ui
                if (prefs.favourites.isNotEmpty() && section == Section.HOME) render(keepFocus = true)
                loadLiveSports()
            }
        }
    }

    private fun loadLiveSports() {
        if (section != Section.HOME) return
        val leagues = SportsCatalog.sports.map { it.leagues.first() } +
            SportsCatalog.sport("football").leagues.drop(1).take(2)
        Bg.run {
            val games = leagues.flatMap { l -> runCatching { SportsFeed.games(l) }.getOrDefault(emptyList()) }
                .filter { it.state == GameState.LIVE }
            val channels = Library.data?.channels?.map { it.key to it.name }
            val cards = games.map { g ->
                val best = channels?.let { GameMatcher.find(g, it).firstOrNull()?.score } ?: 0
                Card.GameCard(g, best >= 50)
            }.sortedByDescending { it.watchable }
            Bg.ui {
                if (isFinishing || section != Section.HOME) return@ui
                val row = if (cards.isEmpty()) null else Row("live-sports", "Live sports now", cards, RowStyle.GAME)
                val had = liveRow != null
                liveRow = row
                when {
                    row != null && had -> rowsAdapter.update(row)
                    row != null || had -> render(keepFocus = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ rows

    private fun switchSection(s: Section) {
        if (section == s) {
            heroPlay.requestFocus(); return
        }
        section = s
        markSection()
        rowsAdapter.forgetScroll()
        val content = findViewById<View>(R.id.content)
        content.animate().alpha(0f).setDuration(140).withEndAction {
            render(keepFocus = false)
            content.translationY = Motion.dp(this, 14f)
            content.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(Motion.settle).start()
        }.start()
        if (s == Section.HOME) loadLiveSports()
    }

    private fun render(keepFocus: Boolean) {
        val s = section
        Bg.run {
            val (rows, feat) = buildRows(s)
            Bg.ui {
                if (isFinishing || isDestroyed || s != section) return@ui
                val first = firstRender
                firstRender = false
                rowsAdapter.submit(rows)
                featured = feat
                heroButtons.visibility = if (feat != null) View.VISIBLE else View.INVISIBLE
                heroPlayText.text = feat?.let { playLabel(it.title) } ?: "Play"
                if (!keepFocus || first) {
                    rowsView.scrollToPosition(0)
                    feat?.let { showHero(it.title, it.kicker, it.match) }
                    if (feat != null) heroPlay.requestFocus()
                    // Once laid out, make sure focus is on the page, not the menu.
                    rowsView.post {
                        val f = currentFocus
                        if (f == null || isIn(rail, f)) contentFocusTarget().requestFocus()
                    }
                }
                if (rows.isEmpty()) {
                    status.visibility = View.VISIBLE
                    status.text = if (s == Section.MYLIST) "Nothing in My List yet.\nHold OK on any film or show, or use + My List on its page."
                    else "Nothing here yet"
                } else status.visibility = View.GONE
                endIntro()
                upgradeBecauseRows(rows)
            }
        }
    }

    private fun playLabel(t: Title): String {
        val w = History.entries(prefs).firstOrNull { it.key == t.key }
        return when {
            w == null -> "Play"
            t.kind == Kind.SHOW && w.episode != null -> "Resume ${w.episode}"
            w.inProgress -> "Resume"
            else -> "Play"
        }
    }

    private fun buildRows(s: Section): Pair<List<Row>, Featured?> {
        val history = History.entries(prefs)
        val profile = Recommender.profile(history)
        val pool = when (s) {
            Section.MOVIES -> Catalog.movies
            Section.SHOWS -> Catalog.shows
            else -> Catalog.all
        }
        val picks = Recommender.topPicks(profile, pool, 40)
        val best = picks.firstOrNull()?.second ?: 0.0
        val match = HashMap<String, Int>()
        picks.forEach { (t, sc) -> match[t.key] = Recommender.matchPercent(sc, best) }
        fun card(t: Title) = Card.TitleCard(t, match = match[t.key] ?: 0)
        val rows = ArrayList<Row>()

        // Continue watching
        val cont = history.filter { it.inProgress || (it.kind == Kind.SHOW && it.episodeIndex >= 0) }
            .filter { s == Section.HOME || s == Section.MYLIST || (s == Section.MOVIES) == (it.kind == Kind.MOVIE) }
            .mapNotNull { w ->
                val t = Catalog.title(w.key) ?: return@mapNotNull null
                Card.TitleCard(
                    t, progress = if (w.durationMs > 0) w.fraction else 0f,
                    subtitle = if (t.kind == Kind.SHOW && w.episode != null) w.episode else "Resume",
                    episodeIndex = w.episodeIndex,
                )
            }.take(20)
        if (cont.isNotEmpty()) rows += Row("continue", "Continue watching", cont, RowStyle.WIDE)

        if (s == Section.MYLIST) {
            val mine = prefs.myList.mapNotNull { Catalog.title(it) }
            if (mine.isNotEmpty()) rows += Row("mylist", "My List", mine.map { card(it) })
            mine.filter { it.kind == Kind.MOVIE }.takeIf { it.size > 1 }?.let { rows += Row("mylist-m", "Films in My List", it.map { t -> card(t) }) }
            mine.filter { it.kind == Kind.SHOW }.takeIf { it.size > 1 }?.let { rows += Row("mylist-s", "Shows in My List", it.map { t -> card(t) }) }
            val feat = mine.firstOrNull()?.let { Featured(it, "In My List", match[it.key] ?: 0) }
            return rows to feat
        }

        if (picks.isNotEmpty()) rows += Row("picks", "Top picks for you", picks.map { card(it.first) })

        // "Because you watched" for your two most recent titles
        val exclude = profile.watched
        for (w in history.filter { s == Section.HOME || (s == Section.MOVIES) == (it.kind == Kind.MOVIE) }.take(2)) {
            val similar = Recommender.similar(w.genres, w.categoryKeys, w.key, pool, exclude)
            if (similar.size >= 4) rows += Row("because:${w.key}", "Because you watched ${w.name}", similar.map { card(it) })
        }

        if (s == Section.HOME) {
            liveRow?.let { rows.add(minOf(rows.size, if (cont.isNotEmpty()) 1 else 0), it) }
            val mine = prefs.myList.mapNotNull { Catalog.title(it) }
            if (mine.isNotEmpty()) rows += Row("mylist", "My List", mine.map { card(it) })
        }

        val movies = if (s == Section.SHOWS) emptyList() else Catalog.movies
        val shows = if (s == Section.MOVIES) emptyList() else Catalog.shows
        fun newest(list: List<Title>) = list.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
            .ifEmpty { list.sortedBy { it.order }.take(40) }
        fun acclaimed(list: List<Title>) = list.filter { (it.rating ?: 0.0) >= 7.3 }.sortedByDescending { it.rating }.take(40)

        if (movies.isNotEmpty()) {
            rows += Row("new-movies", "New films", newest(movies).map { card(it) })
            acclaimed(movies).takeIf { it.size >= 5 }?.let { rows += Row("top-movies", "Critically acclaimed films", it.map { t -> card(t) }) }
        }
        if (shows.isNotEmpty()) {
            rows += Row("new-shows", "New and updated series", newest(shows).map { card(it) })
            acclaimed(shows).takeIf { it.size >= 5 }?.let { rows += Row("top-shows", "Top rated TV", it.map { t -> card(t) }) }
        }

        if (s == Section.HOME) {
            val lib = Library.data
            val favs = prefs.favourites
            if (lib != null && favs.isNotEmpty()) {
                val chans = lib.channels.filter { it.key in favs }
                if (chans.isNotEmpty()) rows += Row("fav-channels", "Your channels", chans.map { Card.ChannelCard(it) }, RowStyle.WIDE)
            }
            // A few genre rows, led by the genres you watch most.
            val counts = HashMap<String, Int>()
            for (t in Catalog.all) for (g in t.genres) counts[g] = (counts[g] ?: 0) + 1
            val liked = profile.genres.entries.sortedByDescending { it.value }.map { it.key }
            val genres = counts.keys.sortedWith(
                compareBy<String> { g -> liked.indexOf(Recommender.genreKey(g)).let { if (it < 0) Int.MAX_VALUE else it } }
                    .thenByDescending { counts[it] ?: 0 }
            ).filter { (counts[it] ?: 0) >= 8 }.take(5)
            for (g in genres) {
                val list = Catalog.all.filter { g in it.genres }.sortedByDescending { it.rating ?: 0.0 }.take(40)
                rows += Row("genre:$g", g, list.map { card(it) })
            }
        } else {
            val cats = if (s == Section.MOVIES) Catalog.movieCategories else Catalog.showCategories
            for (c in cats.take(40)) {
                val list = pool.filter { c.key in it.categoryKeys }.take(40)
                if (list.size >= 3) rows += Row("cat:${c.key}", c.name, list.map { card(it) })
            }
        }

        val feat = picks.firstOrNull { it.first.backdrop != null }?.let { Featured(it.first, "Top pick for you", match[it.first.key] ?: 0) }
            ?: picks.firstOrNull()?.let { Featured(it.first, "Top pick for you", match[it.first.key] ?: 0) }
            ?: (newest(pool).filter { it.backdrop != null }.ifEmpty { newest(pool) }).take(8).randomOrNull()
                ?.let { Featured(it, if (it.kind == Kind.MOVIE) "New film" else "New series", 0) }
        return rows to feat
    }

    /** Swaps in TMDB's "if you liked this" picks (only titles you can play) when a key is set. */
    private fun upgradeBecauseRows(rows: List<Row>) {
        if (prefs.tmdbKey.isBlank()) return
        val targets = rows.filter { it.id.startsWith("because:") }
        if (targets.isEmpty()) return
        val history = History.entries(prefs)
        Bg.run {
            for (row in targets) {
                val key = row.id.removePrefix("because:")
                val t = Catalog.title(key) ?: continue
                val hit = Meta.tmdb(prefs, t) ?: continue
                val recs = Meta.tmdbRecommendations(prefs, t.kind, hit.id)
                val owned = recs.mapNotNull { r -> Catalog.find(r.kind, r.id, r.title, r.year) }
                    .filter { o -> history.none { it.key == o.key } }
                    .distinctBy { it.key }
                if (owned.size < 3) continue
                val merged = (owned + row.cards.mapNotNull { (it as? Card.TitleCard)?.title }).distinctBy { it.key }.take(40)
                val newRow = row.copy(cards = merged.map { Card.TitleCard(it) })
                Bg.ui { if (!isFinishing) rowsAdapter.update(newRow) }
            }
        }
    }

    // ------------------------------------------------------------------ billboard

    private val heroTask = Runnable { pendingHero?.invoke() }
    private var pendingHero: (() -> Unit)? = null

    override fun onCardFocused(card: Card) {
        handler.removeCallbacks(heroTask)
        pendingHero = {
            when (card) {
                is Card.TitleCard -> showHero(card.title, rowTitleFor(card), card.match)
                is Card.ChannelCard -> showChannelHero(card.channel)
                is Card.GameCard -> showGameHero(card.game)
            }
        }
        handler.postDelayed(heroTask, 260)
    }

    private fun rowTitleFor(card: Card): String =
        rowsAdapter.rows.firstOrNull { r -> r.cards.any { it.id == card.id } }?.title ?: ""

    private fun showHero(t: Title, kicker: String, match: Int) {
        Motion.swapText(heroKicker, kicker)
        if (heroShowing == t.key) return
        heroShowing = t.key
        Motion.swapText(heroTitle, t.name)
        heroMatch.visibility = if (match > 0) View.VISIBLE else View.GONE
        heroMatch.text = "$match% match"
        val bits = ArrayList<String>()
        t.year?.let { bits += it }
        bits += if (t.kind == Kind.MOVIE) "Film" else "Series"
        if (t.genres.isNotEmpty()) bits += t.genres.take(3).joinToString(", ")
        t.rating?.let { bits += "★ %.1f".format(it) }
        Motion.swapText(heroMeta, bits.joinToString("   ·   "))
        Motion.swapText(heroPlot, t.plot ?: "")
        heroScores.removeAllViews()
        if (featured?.title?.key == t.key) heroPlayText.text = playLabel(t)

        // Wide picture, plot and critic scores can need a network call: fetch after a short dwell.
        val key = t.key
        Bg.run {
            val provider = Meta.provider(prefs, t)
            val url = t.backdrop ?: provider?.backdrop ?: Meta.tmdb(prefs, t)?.backdrop ?: t.poster
            Bg.ui {
                if (heroShowing != key) return@ui
                setBackdrop(url)
                if (t.plot == null && provider?.plot != null) Motion.swapText(heroPlot, provider.plot)
            }
            if (prefs.omdbKey.isNotBlank()) {
                val sc = Meta.scores(prefs, t)
                Bg.ui { if (heroShowing == key && sc != null) showScoreChips(sc) }
            }
        }
    }

    private fun showScoreChips(sc: Scores) {
        heroScores.removeAllViews()
        scoreChips(this, heroScores, sc)
        heroScores.alpha = 0f
        heroScores.animate().alpha(1f).setDuration(300).start()
    }

    private fun showChannelHero(g: ChannelGroup) {
        heroShowing = "c:" + g.key
        Motion.swapText(heroKicker, "Live channel")
        Motion.swapText(heroTitle, g.name)
        heroMatch.visibility = View.GONE
        Motion.swapText(heroMeta, g.badges)
        Motion.swapText(heroPlot, "Press OK to watch. Hold OK to remove it from your channels.")
        heroScores.removeAllViews()
    }

    private fun showGameHero(game: Game) {
        heroShowing = "g:" + game.id
        Motion.swapText(heroKicker, if (game.state == GameState.LIVE) "Live now  ·  ${game.league.name}" else game.league.name)
        Motion.swapText(heroTitle, game.title)
        heroMatch.visibility = View.GONE
        val score = if (game.headToHead && game.state != GameState.UPCOMING) "${game.home!!.score ?: "-"} – ${game.away!!.score ?: "-"}   ·   " else ""
        Motion.swapText(heroMeta, score + game.detail)
        Motion.swapText(heroPlot, if (game.broadcasts.isNotEmpty()) "Broadcast on ${game.broadcasts.joinToString(", ")}. Press OK to find it in your channels." else "Press OK to find it in your channels.")
        heroScores.removeAllViews()
    }

    private fun setBackdrop(url: String?) {
        if (url == currentBackdrop) return
        currentBackdrop = url
        val back = backdrops[1 - front]
        val old = backdrops[front]
        if (url == null) {
            old.animate().alpha(0f).setDuration(500).start()
            return
        }
        back.load(url) {
            listener(onSuccess = { _, _ ->
                if (currentBackdrop != url) return@listener
                front = 1 - front
                back.animate().cancel()
                back.alpha = 0f
                back.scaleX = 1f; back.scaleY = 1f
                back.animate().alpha(1f).setDuration(650).setInterpolator(Motion.settle).withEndAction {
                    // Slow drift, like a camera move.
                    back.animate().scaleX(1.08f).scaleY(1.08f).setDuration(18_000).setInterpolator(LinearInterpolator()).start()
                }.start()
                old.animate().alpha(0f).setDuration(650).start()
            })
        }
    }

    // ------------------------------------------------------------------ actions

    override fun onCardClicked(card: Card) {
        when (card) {
            is Card.TitleCard -> {
                if (card.progress >= 0f) resume(card) else Playback.openDetails(this, card.title)
            }
            is Card.ChannelCard -> {
                val keys = rowsAdapter.rows.firstOrNull { it.id == "fav-channels" }?.cards
                    ?.mapNotNull { (it as? Card.ChannelCard)?.channel?.key } ?: emptyList()
                Playback.playChannel(this, card.channel.key, keys)
            }
            is Card.GameCard -> Playback.watchGame(this, card.game)
        }
    }

    override fun onCardLongClicked(card: Card) {
        when (card) {
            is Card.TitleCard -> {
                val added = prefs.toggleMyList(card.title.key)
                Toast.makeText(this, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
                if (section == Section.MYLIST) render(keepFocus = true)
            }
            is Card.ChannelCard -> {
                prefs.favourites = prefs.favourites - card.channel.key
                Toast.makeText(this, "Removed ${card.channel.name} from your channels", Toast.LENGTH_SHORT).show()
                render(keepFocus = true)
            }
            else -> {}
        }
    }

    private fun resume(card: Card.TitleCard) {
        val t = card.title
        if (t.kind == Kind.MOVIE) Playback.playMovie(this, t)
        else Playback.playShow(this, t, card.episodeIndex.coerceAtLeast(0)) { e ->
            Toast.makeText(this, XtreamApi.friendlyError(e), Toast.LENGTH_LONG).show()
        }
    }

    private fun play(t: Title) {
        val w = History.entries(prefs).firstOrNull { it.key == t.key }
        if (t.kind == Kind.MOVIE) Playback.playMovie(this, t)
        else Playback.playShow(this, t, w?.episodeIndex?.coerceAtLeast(0) ?: 0) { e ->
            Toast.makeText(this, XtreamApi.friendlyError(e), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    SettingsDialog.show(this) { reload() }; return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (focus != null && !isIn(rail, focus)) {
                    // Only ◀ from the page (or Back) may open the menu.
                    val next = focus.focusSearch(View.FOCUS_LEFT)
                    if (next != null && isIn(rail, next)) railAllowed = true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> if (focus != null && !isIn(rail, focus)) {
                    val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    val next = focus.focusSearch(if (down) View.FOCUS_DOWN else View.FOCUS_UP)
                    if (next == null || isIn(rail, next)) {
                        when {
                            down && isIn(heroButtons, focus) -> firstRowCard()?.requestFocus()
                            !down && isIn(rowsView, focus) && featured != null -> heroPlay.requestFocus()
                        }
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (railOpen) {
                    (lastContentFocus?.takeIf { it.isAttachedToWindow && it.isShown } ?: heroPlay).requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    when {
                        railOpen -> {
                            if (section != Section.HOME) {
                                switchSection(Section.HOME); return true
                            }
                            return super.dispatchKeyEvent(event)
                        }
                        focus != null && isIn(rowsView, focus) && featured != null -> {
                            // Back from the rows jumps to the top, like the big streaming apps.
                            rowsView.smoothScrollToPosition(0)
                            heroPlay.requestFocus()
                            return true
                        }
                        else -> {
                            railAllowed = true
                            nav.firstOrNull { it.view.isSelected }?.view?.requestFocus()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Critic score chips: Tomatometer, Metascore, IMDb. Shared by the billboard and details page. */
fun scoreChips(activity: AppCompatActivity, into: ViewGroup, sc: Scores) {
    fun chip(text: String, color: Int?) {
        val tv = LayoutInflater.from(activity).inflate(R.layout.item_score_chip, into, false) as TextView
        tv.text = text
        if (color != null) tv.setTextColor(ContextCompat.getColor(activity, color))
        into.addView(tv)
    }
    sc.rottenTomatoes?.let { v ->
        val pct = v.trimEnd('%').toIntOrNull() ?: 0
        chip("Tomatometer $v", if (pct >= 60) R.color.fresh else R.color.tomato)
    }
    sc.metacritic?.let { v ->
        val n = v.toIntOrNull() ?: 0
        chip("Metascore $v", if (n >= 61) R.color.fresh else if (n >= 40) R.color.gold else R.color.tomato)
    }
    sc.imdb?.let { chip("IMDb $it", R.color.gold) }
    sc.rated?.let { chip(it, null) }
}
