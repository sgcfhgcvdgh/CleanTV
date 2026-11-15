package com.cleantv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** One film or series: billboard, critic scores and reviews, and more like it. */
class DetailsActivity : AppCompatActivity(), CardHost {

    companion object {
        const val EXTRA_KEY = "key"
    }

    private lateinit var prefs: Prefs
    private lateinit var t: Title
    private lateinit var scroll: ScrollView
    private lateinit var top: LinearLayout
    private lateinit var backdrop: ImageView
    private lateinit var shade: View
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var credits: TextView
    private lateinit var scoresRow: LinearLayout
    private lateinit var criticsStatus: TextView
    private lateinit var reviewAdapter: ReviewAdapter
    private lateinit var moreAdapter: CardAdapter
    private lateinit var playText: TextView
    private var genres: List<String> = emptyList()
    private var runtime: String? = null
    private var rated: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_KEY)?.let { Catalog.title(it) }
        if (title == null) {
            finish(); return
        }
        t = title
        prefs = Prefs(this)
        setContentView(R.layout.activity_details)

        scroll = findViewById(R.id.scroll)
        top = findViewById(R.id.top)
        backdrop = findViewById(R.id.backdrop)
        shade = findViewById(R.id.shade)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        credits = findViewById(R.id.credits)
        scoresRow = findViewById(R.id.scores)
        criticsStatus = findViewById(R.id.criticsStatus)
        playText = findViewById(R.id.playText)
        genres = t.genres

        findViewById<TextView>(R.id.kicker).text = if (t.kind == Kind.MOVIE) "Film" else "Series"
        findViewById<TextView>(R.id.title).text = t.name
        plot.text = t.plot ?: ""
        renderMeta()

        val profile = Recommender.profile(History.entries(prefs))
        if (!profile.isEmpty && t.key !in profile.watched) {
            val best = Recommender.topPicks(profile, Catalog.all, 1).firstOrNull()?.second ?: 0.0
            val pct = Recommender.matchPercent(Recommender.score(profile, t), best)
            if (pct > 0) findViewById<TextView>(R.id.match).apply { text = "$pct% match"; visibility = View.VISIBLE }
        }

        setupButtons()

        val reviews = findViewById<RecyclerView>(R.id.reviews)
        reviews.layoutManager = KeylineLayoutManager(this, RecyclerView.HORIZONTAL)
        reviews.itemAnimator = null
        reviewAdapter = ReviewAdapter()
        reviews.adapter = reviewAdapter

        val more = findViewById<RecyclerView>(R.id.more)
        more.layoutManager = KeylineLayoutManager(this, RecyclerView.HORIZONTAL)
        more.itemAnimator = null
        moreAdapter = CardAdapter(this, RowStyle.POSTER)
        more.adapter = moreAdapter

        // The top section fills the first screen; critics and more-like-this sit below it.
        val root = findViewById<View>(R.id.root)
        root.post { top.minimumHeight = root.height }

        // As you scroll down to the reviews the picture darkens.
        scroll.viewTreeObserver.addOnScrollChangedListener(ViewTreeObserver.OnScrollChangedListener {
            val h = root.height.takeIf { it > 0 } ?: return@OnScrollChangedListener
            shade.alpha = (scroll.scrollY.toFloat() / (h * 0.6f)).coerceIn(0f, 0.85f)
        })
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now != null && isIn(top, now)) scroll.smoothScrollTo(0, 0)
        }

        enter()
        loadExtras()
    }

    override fun onResume() {
        super.onResume()
        if (::playText.isInitialized) playText.text = playLabel()
    }

    private fun isIn(parent: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /** Lines of the billboard rise in one after another. */
    private fun enter() {
        var delay = 80L
        for (i in 0 until top.childCount) {
            val c = top.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            Motion.rise(c, delay)
            delay += 60
        }
        findViewById<View>(R.id.play).requestFocus()
    }

    private fun renderMeta() {
        val bits = ArrayList<String>()
        t.year?.let { bits += it }
        rated?.let { bits += it }
        runtime?.let { bits += it }
        if (t.kind == Kind.SHOW) bits += "Series"
        if (genres.isNotEmpty()) bits += genres.take(3).joinToString(", ")
        t.rating?.let { bits += "★ %.1f".format(it) }
        meta.text = bits.joinToString("   ·   ")
    }

    private fun playLabel(): String {
        val w = History.entries(prefs).firstOrNull { it.key == t.key }
        return when {
            w == null -> "Play"
            t.kind == Kind.SHOW && w.episode != null -> "Resume ${w.episode}"
            w.inProgress -> "Resume"
            else -> "Play"
        }
    }

    private fun setupButtons() {
        val play = findViewById<View>(R.id.play)
        val episodes = findViewById<View>(R.id.episodes)
        val myList = findViewById<View>(R.id.myList)
        val versions = findViewById<View>(R.id.versions)
        val myIcon = findViewById<ImageView>(R.id.myListIcon)
        listOf(play, episodes, myList, versions).forEach { b -> b.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.06f) } }

        playText.text = playLabel()
        play.setOnClickListener {
            if (t.kind == Kind.MOVIE) Playback.playMovie(this, t)
            else {
                val w = History.entries(prefs).firstOrNull { it.key == t.key }
                Playback.playShow(this, t, w?.episodeIndex?.coerceAtLeast(0) ?: 0) { e ->
                    Toast.makeText(this, XtreamApi.friendlyError(e), Toast.LENGTH_LONG).show()
                }
            }
        }

        if (t.kind == Kind.SHOW) {
            episodes.visibility = View.VISIBLE
            episodes.setOnClickListener {
                startActivity(Intent(this, ShowActivity::class.java).putExtra(ShowActivity.EXTRA_SHOW, t.ref))
            }
        }

        fun syncList() = myIcon.setImageResource(if (t.key in prefs.myList) R.drawable.ic_check else R.drawable.ic_add)
        syncList()
        myList.setOnClickListener {
            val added = prefs.toggleMyList(t.key)
            syncList()
            myIcon.animate().cancel()
            myIcon.scaleX = 0.4f; myIcon.scaleY = 0.4f
            myIcon.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.settle).start()
            Toast.makeText(this, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
        }

        if (t.kind == Kind.MOVIE && t.ids.size > 1) {
            versions.visibility = View.VISIBLE
            findViewById<TextView>(R.id.versionsText).text = "${t.ids.size} versions"
            versions.setOnClickListener {
                val labels = t.ids.mapIndexed { i, id -> "Copy ${i + 1}  ·  ${Catalog.movieExt(id).uppercase()}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Play which copy?")
                    .setItems(labels) { _, which ->
                        val ids = listOf(t.ids[which]) + t.ids.filterIndexed { i, _ -> i != which }
                        Playback.playMovie(this, t.copy(ids = ids))
                    }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ outside data

    private fun loadExtras() {
        // Backdrop and provider detail (plot, cast, genres).
        Bg.run {
            val provider = Meta.provider(prefs, t)
            val tmdb = Meta.tmdb(prefs, t)
            val url = t.backdrop ?: provider?.backdrop ?: tmdb?.backdrop ?: t.poster
            Bg.ui {
                if (isFinishing) return@ui
                if (url != null) backdrop.load(url) {
                    listener(onSuccess = { _, _ ->
                        backdrop.scaleX = 1.04f; backdrop.scaleY = 1.04f
                        backdrop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(Motion.settle)
                            .withLayer().start()
                    })
                }
                if (plot.text.isNullOrBlank()) plot.text = provider?.plot ?: tmdb?.overview ?: ""
                val more = Catalog.genresOf(provider?.genre) + (tmdb?.genres ?: emptyList())
                if (more.isNotEmpty()) {
                    genres = (genres + more).distinct()
                    History.addGenres(prefs, t.key, genres)
                }
                if (runtime == null) runtime = provider?.duration?.let { prettyDuration(it) }
                if (rated == null) rated = provider?.age
                renderMeta()
                setCredits(provider?.director, provider?.cast)
            }
            loadMoreLikeThis(tmdb)
        }

        // Critic scores and reviews.
        Bg.run {
            val sc = Meta.scores(prefs, t)
            val reviews = Meta.reviews(prefs, t)
            Bg.ui {
                if (isFinishing) return@ui
                val items = ArrayList<ReviewItem>()
                if (sc != null) {
                    scoreChips(this, scoresRow, sc)
                    Motion.rise(scoresRow)
                    sc.rottenTomatoes?.let { items += ReviewItem.Score("Rotten Tomatoes", it, "Tomatometer · critics' reviews that are positive") }
                    sc.metacritic?.let { items += ReviewItem.Score("Metacritic", "$it / 100", "Metascore · weighted average of critics") }
                    sc.imdb?.let { items += ReviewItem.Score("IMDb", "$it / 10", "Audience rating") }
                    sc.awards?.let { items += ReviewItem.Score("Awards", "", it) }
                    if (runtime == null) runtime = sc.runtime
                    if (rated == null) rated = sc.rated
                    renderMeta()
                    if (credits.visibility != View.VISIBLE) setCredits(sc.director, sc.actors)
                    if (plot.text.isNullOrBlank()) plot.text = sc.plot ?: ""
                }
                reviews.forEach { items += ReviewItem.Critic(it) }
                reviewAdapter.submit(items)
                criticsStatus.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                criticsStatus.text = when {
                    prefs.omdbKey.isBlank() && reviews.isEmpty() ->
                        "No reviews found. Add a free OMDb key in Settings → Critic reviews to see Rotten Tomatoes, Metacritic and IMDb scores."
                    else -> "No critic reviews found for ${t.label}."
                }
            }
        }
    }

    private fun loadMoreLikeThis(tmdb: TmdbHit?) {
        val history = History.entries(prefs)
        val watched = history.map { it.key }.toSet()
        val owned = if (tmdb != null) {
            Meta.tmdbRecommendations(prefs, t.kind, tmdb.id)
                .mapNotNull { r -> Catalog.find(r.kind, r.id, r.title, r.year) }
                .filter { it.key != t.key }
        } else emptyList()
        val pool = if (t.kind == Kind.MOVIE) Catalog.movies else Catalog.shows
        val similar = Recommender.similar(genres, t.categoryKeys, t.key, pool, watched, 30)
        val list = (owned + similar).distinctBy { it.key }.take(30)
        Bg.ui {
            if (isFinishing || list.isEmpty()) return@ui
            findViewById<View>(R.id.moreTitle).visibility = View.VISIBLE
            moreAdapter.submit(list.map { Card.TitleCard(it) })
        }
    }

    private fun setCredits(director: String?, cast: String?) {
        val parts = ArrayList<String>()
        director?.takeIf { it.isNotBlank() }?.let { parts += "Director: $it" }
        cast?.takeIf { it.isNotBlank() }?.let { parts += "Starring: " + it.split(',').take(4).joinToString(", ") { s -> s.trim() } }
        if (parts.isEmpty()) return
        credits.text = parts.joinToString("\n")
        credits.visibility = View.VISIBLE
    }

    /** "01:52:00", "112", "112 min" -> "1h 52m". */
    private fun prettyDuration(raw: String): String? {
        val hms = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(raw.trim())
        val mins = when {
            hms != null -> hms.groupValues[1].toInt() * 60 + hms.groupValues[2].toInt()
            else -> Regex("(\\d+)").find(raw)?.value?.toIntOrNull()
        } ?: return null
        if (mins <= 0) return null
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }

    // ------------------------------------------------------------------ cards

    override fun onCardFocused(card: Card) {}

    override fun onCardClicked(card: Card) {
        if (card is Card.TitleCard) Playback.openDetails(this, card.title)
    }

    override fun onCardLongClicked(card: Card) {
        if (card is Card.TitleCard) {
            val added = prefs.toggleMyList(card.title.key)
            Toast.makeText(this, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
        }
    }
}

sealed class ReviewItem {
    data class Score(val outlet: String, val value: String, val caption: String) : ReviewItem()
    data class Critic(val review: Review) : ReviewItem()
}

class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.VH>() {
    private var items: List<ReviewItem> = emptyList()

    fun submit(list: List<ReviewItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val outlet: TextView = v.findViewById(R.id.outlet)
        val stars: TextView = v.findViewById(R.id.stars)
        val headline: TextView = v.findViewById(R.id.headline)
        val summary: TextView = v.findViewById(R.id.summary)
        val byline: TextView = v.findViewById(R.id.byline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_review, parent, false)
        v.clipToOutline = true
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        when (val it = items[position]) {
            is ReviewItem.Score -> {
                h.outlet.text = it.outlet
                h.stars.text = ""
                h.headline.text = it.value
                h.headline.textSize = if (it.value.isEmpty()) 0f else 30f
                h.headline.visibility = if (it.value.isEmpty()) View.GONE else View.VISIBLE
                h.summary.text = it.caption
                h.summary.maxLines = if (it.value.isEmpty()) 5 else 3
                h.byline.text = ""
            }
            is ReviewItem.Critic -> {
                val r = it.review
                h.outlet.text = r.outlet
                h.stars.text = r.stars?.let { n -> "★".repeat(n) + "☆".repeat(5 - n) } ?: ""
                h.headline.visibility = View.VISIBLE
                h.headline.textSize = 16f
                h.headline.text = r.headline
                h.summary.maxLines = 3
                h.summary.text = r.summary ?: ""
                h.byline.text = listOfNotNull(r.critic, r.date).joinToString("  ·  ")
            }
        }
        h.itemView.setOnFocusChangeListener { v, has -> Motion.focus(v, has, 1.05f) }
        h.itemView.setOnClickListener { }
    }
}
