package com.cleantv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

/** Search films, series and channels as you type. */
class SearchActivity : AppCompatActivity(), CardHost {

    private lateinit var prefs: Prefs
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var rowsAdapter: RowsAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        val rows = findViewById<RecyclerView>(R.id.rows)
        rowsAdapter = RowsAdapter(this)
        rows.layoutManager = KeylineLayoutManager(this, RecyclerView.VERTICAL)
        rows.itemAnimator = null
        rows.adapter = rowsAdapter
        findViewById<android.view.View>(R.id.root).viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            var p: android.view.View? = now
            while (p != null && p !== rows) p = p.parent as? android.view.View
            Rows.dim(rows, if (p != null) now else null, idle = 1f)
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                val r = Runnable { search(q) }
                pending = r
                handler.postDelayed(r, 280)
            }
        })
        query.requestFocus()
        search("")
        // Channels are searched too once they've loaded.
        if (Library.data == null) Playback.withChannels(this) { search(query.text.toString().trim()) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun search(q: String) {
        val gen = ++generation
        Bg.run {
            val rows = ArrayList<Row>()
            if (q.isEmpty()) {
                val profile = Recommender.profile(History.entries(prefs))
                val picks = Recommender.topPicks(profile, Catalog.all, 30).map { it.first }
                    .ifEmpty { Catalog.all.filter { (it.rating ?: 0.0) >= 7.5 }.sortedByDescending { it.rating }.take(30) }
                if (picks.isNotEmpty()) rows += Row("suggest", "Suggested for you", picks.map { Card.TitleCard(it) })
            } else {
                val words = q.lowercase().split(' ').filter { it.isNotBlank() }
                fun hit(name: String) = name.lowercase().let { n -> words.all { n.contains(it) } }
                // Exact-start matches first, then by rating.
                fun rank(list: List<Title>) = list.filter { hit(it.name) }
                    .sortedWith(compareByDescending<Title> { it.name.startsWith(q, ignoreCase = true) }.thenByDescending { it.rating ?: 0.0 })
                    .take(60)
                val movies = rank(Catalog.movies)
                val shows = rank(Catalog.shows)
                val chans = Library.data?.channels?.filter { hit(it.name) }?.take(60) ?: emptyList()
                if (movies.isNotEmpty()) rows += Row("films", "Films", movies.map { Card.TitleCard(it) })
                if (shows.isNotEmpty()) rows += Row("series", "Series", shows.map { Card.TitleCard(it) })
                if (chans.isNotEmpty()) rows += Row("channels", "Channels", chans.map { Card.ChannelCard(it) }, RowStyle.WIDE)
            }
            val count = rows.sumOf { it.cards.size }
            Bg.ui {
                if (gen != generation || isFinishing) return@ui
                rowsAdapter.forgetScroll()
                rowsAdapter.submit(rows)
                status.text = when {
                    q.isEmpty() -> ""
                    count == 0 -> "Nothing matches “$q”"
                    else -> "$count results for “$q”"
                }
            }
        }
    }

    override fun onCardFocused(card: Card) {}

    override fun onCardClicked(card: Card) {
        when (card) {
            is Card.TitleCard -> Playback.openDetails(this, card.title)
            is Card.ChannelCard -> {
                val keys = rowsAdapter.rows.firstOrNull { it.id == "channels" }?.cards
                    ?.mapNotNull { (it as? Card.ChannelCard)?.channel?.key } ?: emptyList()
                Playback.playChannel(this, card.channel.key, keys)
            }
            is Card.GameCard -> Playback.watchGame(this, card.game)
        }
    }

    override fun onCardLongClicked(card: Card) {
        if (card is Card.TitleCard) {
            val added = prefs.toggleMyList(card.title.key)
            Toast.makeText(this, if (added) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
        }
    }
}
