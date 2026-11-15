package com.cleantv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

class ChannelAdapter(
    private val onClick: (ChannelGroup) -> Unit,
    private val onLongClick: (ChannelGroup) -> Unit,
    private val isFavourite: (ChannelGroup) -> Boolean,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<ChannelGroup> = emptyList()
        private set

    fun submit(list: List<ChannelGroup>) {
        items = list
        notifyDataSetChanged()
    }

    fun refresh(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) notifyItemChanged(i)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val badges: TextView = v.findViewById(R.id.badges)
        val fav: TextView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.name
        holder.badges.text = g.badges
        holder.fav.visibility = if (isFavourite(g)) View.VISIBLE else View.GONE
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
            val s = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}

class ShowAdapter(
    private val onClick: (Show) -> Unit,
) : RecyclerView.Adapter<ShowAdapter.VH>() {

    var items: List<Show> = emptyList()
        private set

    fun submit(list: List<Show>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val name: TextView = v.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_show, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        if (s.cover != null) {
            holder.poster.load(s.cover) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            holder.poster.dispose()
            holder.poster.setImageResource(R.drawable.ic_tv)
        }
        holder.itemView.setOnClickListener { onClick(s) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val sc = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
        }
    }
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
