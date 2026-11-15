package com.cleantv.app

import android.content.Context
import android.graphics.Rect
import android.os.Parcelable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

// ======================================================================
// Motion
// ======================================================================

object Motion {
    /** Fast out, long settle: the curve every focus and scroll uses. */
    val settle: Interpolator = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)
    val scroll: Interpolator = DecelerateInterpolator(2.2f)

    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
    fun dp(v: View, value: Float): Int = (value * v.resources.displayMetrics.density).toInt()

    /** The card grows, lifts and gets its ring (ring via the foreground selector). */
    fun focus(v: View, focused: Boolean, scale: Float = 1.1f) {
        v.animate().cancel()
        v.animate()
            .scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
            .setDuration(if (focused) 240 else 180)
            .setInterpolator(settle)
            .start()
    }

    /** Fades a view in while it rises a little. */
    fun rise(v: View, delay: Long = 0, distanceDp: Float = 18f) {
        v.alpha = 0f
        v.translationY = dp(v.context, distanceDp)
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420).setInterpolator(settle).start()
    }

    /** Swaps a text with a quick fade. */
    fun swapText(tv: TextView, text: CharSequence?) {
        if (tv.text?.toString() == text?.toString()) return
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(110).withEndAction {
            tv.text = text
            tv.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            tv.animate().alpha(1f).setDuration(220).setInterpolator(settle).start()
        }.start()
    }
}

/**
 * Keeps the focused item on a fixed line (the left edge of a row, the top of the rows area)
 * and glides the list to it, the way streaming apps on TV move.
 */
class KeylineLayoutManager(context: Context, orientation: Int) : LinearLayoutManager(context, orientation, false) {

    init {
        initialPrefetchItemCount = 8
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val delta = if (orientation == HORIZONTAL) getDecoratedLeft(child) - paddingLeft
        else getDecoratedTop(child) - paddingTop
        if (delta == 0) return false
        if (orientation == HORIZONTAL) {
            if (immediate) parent.scrollBy(delta, 0) else parent.smoothScrollBy(delta, 0, Motion.scroll)
        } else {
            if (immediate) parent.scrollBy(0, delta) else parent.smoothScrollBy(0, delta, Motion.scroll)
        }
        return true
    }

    // Look further ahead so the next cards are already laid out when you press right.
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        if (orientation == HORIZONTAL) {
            extraLayoutSpace[0] = width / 4
            extraLayoutSpace[1] = width / 2
        } else {
            extraLayoutSpace[0] = 0
            extraLayoutSpace[1] = height / 3
        }
    }
}

// ======================================================================
// Cards and rows
// ======================================================================

sealed class Card {
    abstract val id: String

    data class TitleCard(
        val title: Title,
        val progress: Float = -1f,
        val subtitle: String? = null,
        val match: Int = 0,
        val episodeIndex: Int = -1,
    ) : Card() {
        override val id get() = title.key
    }

    data class ChannelCard(val channel: ChannelGroup) : Card() {
        override val id get() = "c:" + channel.key
    }

    data class GameCard(val game: Game, val watchable: Boolean) : Card() {
        override val id get() = "g:" + game.id
    }
}

enum class RowStyle { POSTER, WIDE, GAME }

data class Row(val id: String, val title: String, val cards: List<Card>, val style: RowStyle = RowStyle.POSTER)

interface CardHost {
    fun onCardFocused(card: Card)
    fun onCardClicked(card: Card)
    fun onCardLongClicked(card: Card) {}
}

class CardAdapter(private val host: CardHost, private val style: RowStyle) : RecyclerView.Adapter<CardAdapter.VH>() {

    var items: List<Card> = emptyList()
        private set

    init {
        // Stable ids let a refreshed row keep focus on the same card.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    fun submit(list: List<Card>) {
        if (list == items) return
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val name: TextView? = v.findViewById(R.id.name)
        val sub: TextView? = v.findViewById(R.id.sub)
        val progress: ProgressBar? = v.findViewById(R.id.progress)
        val badge: TextView? = v.findViewById(R.id.badge)
        // Game cards
        val league: TextView? = v.findViewById(R.id.league)
        val status: TextView? = v.findViewById(R.id.status)
        val homeLogo: ImageView? = v.findViewById(R.id.homeLogo)
        val homeName: TextView? = v.findViewById(R.id.homeName)
        val homeScore: TextView? = v.findViewById(R.id.homeScore)
        val awayLogo: ImageView? = v.findViewById(R.id.awayLogo)
        val awayName: TextView? = v.findViewById(R.id.awayName)
        val awayScore: TextView? = v.findViewById(R.id.awayScore)
        val teams: View? = v.findViewById(R.id.teams)
        val event: TextView? = v.findViewById(R.id.event)
        val watch: TextView? = v.findViewById(R.id.watch)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Card.GameCard -> 2
        is Card.ChannelCard -> 1
        is Card.TitleCard -> if (style == RowStyle.WIDE) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            2 -> R.layout.item_card_game
            1 -> R.layout.item_card_wide
            else -> R.layout.item_card_poster
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.clipToOutline = true
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val card = items[position]
        when (card) {
            is Card.TitleCard -> bindTitle(h, card)
            is Card.ChannelCard -> bindChannel(h, card)
            is Card.GameCard -> bindGame(h, card)
        }
        h.itemView.setOnClickListener { host.onCardClicked(card) }
        h.itemView.setOnKeyListener { v, keyCode, e ->
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN &&
                h.bindingAdapterPosition == items.size - 1 && Rows.jumpToNextRow(v)
        }
        h.itemView.setOnLongClickListener { host.onCardLongClicked(card); true }
        h.itemView.setOnFocusChangeListener { v, has ->
            Motion.focus(v, has, if (card is Card.TitleCard && style == RowStyle.POSTER) 1.12f else 1.07f)
            if (has) host.onCardFocused(card)
        }
        h.itemView.contentDescription = when (card) {
            is Card.TitleCard -> card.title.label
            is Card.ChannelCard -> card.channel.name
            is Card.GameCard -> card.game.title
        }
    }

    override fun onViewRecycled(h: VH) {
        h.itemView.animate().cancel()
        h.itemView.scaleX = 1f; h.itemView.scaleY = 1f; h.itemView.translationZ = 0f
    }

    private fun bindTitle(h: VH, c: Card.TitleCard) {
        val t = c.title
        h.name?.text = t.name
        h.sub?.text = c.subtitle ?: listOfNotNull(t.year, t.genres.firstOrNull()).joinToString("  ·  ")
        h.progress?.let {
            it.visibility = if (c.progress >= 0f) View.VISIBLE else View.GONE
            it.progress = (c.progress * 1000).toInt()
        }
        h.badge?.visibility = View.GONE
        val url = if (style == RowStyle.WIDE) t.backdrop ?: t.poster else t.poster
        h.image.scaleType = ImageView.ScaleType.CENTER_CROP
        h.image.setPadding(0, 0, 0, 0)
        if (url != null) {
            h.image.load(url) {
                crossfade(250)
                listener(onError = { _, _ -> h.image.setImageDrawable(null) })
            }
        } else {
            h.image.dispose()
            h.image.setImageDrawable(null)
        }
        // The title is printed on the card when there's no artwork, or on wide cards always.
        h.name?.visibility = if (url == null || style == RowStyle.WIDE) View.VISIBLE else View.GONE
    }

    private fun bindChannel(h: VH, c: Card.ChannelCard) {
        val g = c.channel
        h.name?.text = g.name
        h.name?.visibility = View.VISIBLE
        h.sub?.text = g.badges
        h.progress?.visibility = View.GONE
        h.badge?.visibility = View.GONE
        h.image.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = Motion.dp(h.image, 22f)
        h.image.setPadding(pad, pad / 2, pad, pad + pad / 2)
        if (g.icon != null) h.image.load(g.icon) { error(R.drawable.ic_tv) } else {
            h.image.dispose(); h.image.setImageResource(R.drawable.ic_tv)
        }
    }

    private fun bindGame(h: VH, c: Card.GameCard) {
        val g = c.game
        h.league?.text = g.league.name
        h.status?.let { s ->
            s.text = when (g.state) {
                GameState.LIVE -> "LIVE  " + g.detail
                else -> g.detail
            }
            s.setBackgroundResource(if (g.state == GameState.LIVE) R.drawable.bg_chip_live else R.drawable.bg_chip)
        }
        if (g.headToHead) {
            h.teams?.visibility = View.VISIBLE
            h.event?.visibility = View.GONE
            val home = g.home!!
            val away = g.away!!
            h.homeName?.text = home.short
            h.awayName?.text = away.short
            val showScore = g.state != GameState.UPCOMING
            h.homeScore?.text = if (showScore) home.score ?: "" else ""
            h.awayScore?.text = if (showScore) away.score ?: "" else ""
            h.homeLogo?.let { iv -> if (home.logo != null) iv.load(home.logo) else { iv.dispose(); iv.setImageDrawable(null) } }
            h.awayLogo?.let { iv -> if (away.logo != null) iv.load(away.logo) else { iv.dispose(); iv.setImageDrawable(null) } }
        } else {
            h.teams?.visibility = View.GONE
            h.event?.visibility = View.VISIBLE
            h.event?.text = g.eventName
        }
        h.watch?.text = when {
            g.state == GameState.FINISHED -> "Full time"
            c.watchable -> "▶  On your channels"
            g.broadcasts.isNotEmpty() -> g.broadcasts.take(3).joinToString(" · ")
            else -> "Find a channel"
        }
        h.watch?.setTextColor(ContextCompat.getColor(h.itemView.context, if (c.watchable && g.state != GameState.FINISHED) R.color.accent else R.color.muted))
    }
}

object Rows {
    private fun isIn(parent: View, v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * The row you're on stays bright, rows below dim, and rows you've moved past fade out
     * completely so they never sit behind the billboard text.
     */
    fun dim(rows: RecyclerView, focus: View?, idle: Float) {
        val focusedRow = (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { isIn(it, focus) }
        val focusedPos = focusedRow?.let { rows.getChildAdapterPosition(it) } ?: -1
        for (i in 0 until rows.childCount) {
            val child = rows.getChildAt(i)
            val pos = rows.getChildAdapterPosition(child)
            val target = when {
                focusedPos < 0 -> idle
                child === focusedRow -> 1f
                pos in 0 until focusedPos -> 0f
                else -> 1f
            }
            if (child.alpha != target) child.animate().alpha(target).setDuration(220).start()
        }
    }

    /** Right on the last card of a row moves to the start of the next row. */
    fun jumpToNextRow(card: View): Boolean {
        val inner = card.parent as? RecyclerView ?: return false
        val rowView = inner.parent as? View ?: return false
        val outer = rowView.parent as? RecyclerView ?: return false
        val pos = outer.getChildAdapterPosition(rowView)
        val count = outer.adapter?.itemCount ?: 0
        if (pos == RecyclerView.NO_POSITION || pos + 1 >= count) return false
        fun focusNext() {
            val vh = outer.findViewHolderForAdapterPosition(pos + 1) as? RowsAdapter.VH ?: return
            vh.list.scrollToPosition(0)
            vh.list.post {
                (vh.list.findViewHolderForAdapterPosition(0)?.itemView ?: vh.list.getChildAt(0))?.requestFocus()
            }
        }
        if (outer.findViewHolderForAdapterPosition(pos + 1) != null) focusNext()
        else {
            outer.scrollToPosition(pos + 1)
            outer.post { focusNext() }
        }
        return true
    }
}

/** Vertical list of rows; each row a horizontal list of cards that remembers where you were. */
class RowsAdapter(private val host: CardHost) : RecyclerView.Adapter<RowsAdapter.VH>() {

    var rows: List<Row> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = rows[position].id.hashCode().toLong()

    private val scrollState = HashMap<String, Parcelable?>()
    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 30); setMaxRecycledViews(1, 20); setMaxRecycledViews(2, 16)
    }

    fun submit(list: List<Row>) {
        val sameRows = rows.map { it.id } == list.map { it.id }
        rows = list
        // Same rows in the same order: refresh their cards in place so nothing jumps.
        if (sameRows && list.isNotEmpty()) notifyItemRangeChanged(0, list.size, REFRESH) else notifyDataSetChanged()
    }

    /** Replaces one row's cards in place (e.g. live scores refreshing) without moving focus. */
    fun update(row: Row) {
        val i = rows.indexOfFirst { it.id == row.id }
        if (i < 0) return
        rows = rows.toMutableList().also { it[i] = row }
        notifyItemChanged(i, row)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        var rowId: String? = null
    }

    override fun getItemViewType(position: Int) = rows[position].style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        val h = VH(v)
        h.list.layoutManager = KeylineLayoutManager(parent.context, RecyclerView.HORIZONTAL)
        h.list.setRecycledViewPool(pool)
        h.list.itemAnimator = null
        h.list.adapter = CardAdapter(host, RowStyle.entries[viewType])
        h.list.setHasFixedSize(true)
        return h
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            (h.list.adapter as CardAdapter).let { a ->
                val row = rows[position]
                // Keep the same number of cards in place so focus doesn't jump.
                a.submit(row.cards)
            }
            return
        }
        super.onBindViewHolder(h, position, payloads)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.rowId = row.id
        h.title.text = row.title
        // A recycled row may still be faded out from higher up the page.
        h.itemView.animate().cancel()
        h.itemView.alpha = 1f
        (h.list.adapter as CardAdapter).submit(row.cards)
        val saved = scrollState[row.id]
        if (saved != null) h.list.layoutManager?.onRestoreInstanceState(saved) else h.list.scrollToPosition(0)
    }

    override fun onViewRecycled(h: VH) {
        h.rowId?.let { scrollState[it] = h.list.layoutManager?.onSaveInstanceState() }
    }

    fun forgetScroll() = scrollState.clear()

    private object REFRESH
}
