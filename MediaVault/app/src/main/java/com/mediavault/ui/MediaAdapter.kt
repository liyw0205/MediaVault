package com.mediavault.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.data.MediaItem
import com.mediavault.databinding.ItemMediaBinding

class MediaAdapter(
    private var items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    fun submit(list: List<MediaItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem) {
            b.titleText.text = item.displayTitle()
            val sub = buildList {
                if (item.year.isNotBlank()) add(item.year)
                if (item.genres.isNotEmpty()) add(item.genres.joinToString(" · "))
                if (item.tags.isNotEmpty()) add(item.tags.take(3).joinToString(" "))
            }.joinToString(" · ")
            b.subtitleText.text = sub.ifBlank { " " }
            b.pathText.text = item.path
            b.root.setOnClickListener { onClick(item) }
        }
    }
}