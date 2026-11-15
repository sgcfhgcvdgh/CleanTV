package com.cleantv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

data class CatItem(val key: String, val name: String, val count: Int)

class CategoryAdapter(
    private val onSelect: (item: CatItem, fromFocus: Boolean) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<CatItem> = emptyList()
        private set
    var selectedKey: String? = null
        private set

    fun submit(list: List<CatItem>, selected: String?) {
        items = list
        selectedKey = selected
        notifyDataSetChanged()
    }

    fun updateCount(key: String, count: Int) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) {
            items = items.toMutableList().also { it[i] = it[i].copy(count = count) }
            notifyItemChanged(i)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = item.count.toString()
        holder.itemView.isSelected = item.key == selectedKey
        holder.itemView.setOnClickListener { select(item, false) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) select(item, true) }
    }

    private var recycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    private fun select(item: CatItem, fromFocus: Boolean) {
        if (selectedKey != item.key) {
            selectedKey = item.key
            // Update the highlight directly (rebinding during a focus change can upset RecyclerView).
            recycler?.let { rv ->
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos in items.indices) child.isSelected = items[pos].key == selectedKey
                }
            }
        }
        onSelect(item, fromFocus)
    }
}

/** Live TV as a list: logo, name and qualities, then what's on now (with progress) and next. */
class ChannelListAdapter(
    private val prefs: Prefs,
    private val onClick: (ChannelGroup) -> Unit,
    private val onLongClick: (ChannelGroup) -> Unit,
    private val onFocus: (ChannelGroup) -> Unit = {},
) : RecyclerView.Adapter<ChannelListAdapter.VH>() {

    var items: List<ChannelGroup> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    fun submit(list: List<ChannelGroup>) {
        items = list
        notifyDataSetChanged()
    }

    fun refresh(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) notifyItemChanged(i)
    }

    /** Moves the progress bars and rolls over to the next programme. */
    fun tick() = notifyItemRangeChanged(0, items.size, GUIDE)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val badges: TextView = v.findViewById(R.id.badges)
        val fav: TextView = v.findViewById(R.id.fav)
        val nowTime: TextView = v.findViewById(R.id.nowTime)
        val nowTitle: TextView = v.findViewById(R.id.nowTitle)
        val nowProgress: ProgressBar = v.findViewById(R.id.nowProgress)
        val next: TextView = v.findViewById(R.id.next)
        var key: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_row, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(GUIDE)) bindGuide(holder, items[position]) else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.key = g.key
        holder.name.text = g.name
        holder.badges.text = g.badges
        holder.fav.visibility = if (prefs.favourites.contains(g.key)) View.VISIBLE else View.GONE
        if (g.icon != null) {
            holder.logo.load(g.icon) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            holder.logo.dispose()
            holder.logo.setImageResource(R.drawable.ic_tv)
        }
        holder.itemView.setOnClickListener { onClick(g) }
        holder.itemView.setOnLongClickListener { onLongClick(g); true }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.02f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(Motion.settle).start()
            if (hasFocus) onFocus(g)
        }
        bindGuide(holder, g)
    }

    private fun bindGuide(h: VH, g: ChannelGroup) {
        val id = Epg.streamOf(g)
        val cached = id?.let { Epg.cached(it) }
        if (cached != null) {
            showGuide(h, cached)
            return
        }
        h.nowTime.text = ""
        h.nowTitle.text = ""
        h.nowProgress.visibility = View.INVISIBLE
        h.next.text = ""
        Epg.fetch(prefs, g) { list -> if (h.key == g.key) showGuide(h, list) }
    }

    private fun showGuide(h: VH, list: List<Programme>) {
        val now = System.currentTimeMillis()
        val cur = list.firstOrNull { it.start <= now && it.end > now }
        val next = list.firstOrNull { it.start >= (cur?.end ?: now) }
        if (cur == null && next == null) {
            h.nowTime.text = ""
            h.nowTitle.text = "No guide information"
            h.nowTitle.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.faint))
            h.nowProgress.visibility = View.INVISIBLE
            h.next.text = ""
            return
        }
        h.nowTitle.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text))
        if (cur != null) {
            h.nowTime.text = "${Epg.time(cur.start)} – ${Epg.time(cur.end)}"
            h.nowTitle.text = cur.title
            h.nowProgress.visibility = View.VISIBLE
            h.nowProgress.progress = Epg.progress(cur, now)
        } else {
            h.nowTime.text = ""
            h.nowTitle.text = "Off air"
            h.nowProgress.visibility = View.INVISIBLE
        }
        h.next.text = next?.let { "Next  ${Epg.time(it.start)}  ${it.title}" } ?: ""
    }

    private object GUIDE
}

class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
    private val hasProgress: (Episode) -> Boolean,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    var items: List<Episode> = emptyList()
        private set

    fun submit(list: List<Episode>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val title: TextView = v.findViewById(R.id.title)
        val copies: TextView = v.findViewById(R.id.copies)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.number.text = if (e.episode > 0) "E%02d".format(e.episode) else "•"
        holder.title.text = e.title.ifEmpty { if (e.episode > 0) "Episode ${e.episode}" else "Episode" }
        val extra = ArrayList<String>()
        if (hasProgress(e)) extra += "▶ in progress"
        if (e.sources.size > 1) extra += "${e.sources.size} copies"
        holder.copies.text = extra.joinToString("   ")
        holder.itemView.setOnClickListener { onClick(e) }
    }
}
