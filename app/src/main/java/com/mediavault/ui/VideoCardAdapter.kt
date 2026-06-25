package com.mediavault.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.R
import com.mediavault.data.MediaItem
import java.io.File

class VideoCardAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: ((MediaItem) -> Unit)? = null,
) : ListAdapter<MediaItem, VideoCardAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video_card, parent, false)
        return VH(v, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        itemView: View,
        private val onClick: (MediaItem) -> Unit,
        private val onLongClick: ((MediaItem) -> Unit)?,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.titleText)
        private val meta: TextView = itemView.findViewById(R.id.metaText)
        private val cover: ImageView = itemView.findViewById(R.id.coverImage)
        private val placeholder: TextView = itemView.findViewById(R.id.coverPlaceholder)
        private val chips: com.google.android.material.chip.ChipGroup = itemView.findViewById(R.id.tagChips)

        fun bind(item: MediaItem) {
            title.text = item.displayTitle()
            val ep = item.episodeLabel()
            meta.text = ep
            meta.visibility = if (ep.isBlank()) View.GONE else View.VISIBLE

            val local = item.coverLocalPath()
            if (local != null && File(local).isFile) {
                cover.setImageBitmap(BitmapFactory.decodeFile(local))
                cover.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            } else {
                cover.setImageDrawable(null)
                cover.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
            }

            chips.removeAllViews()
            val tags = (item.tags + item.genres).distinct().take(4)
            val ctx = itemView.context
            for (t in tags) {
                val chip = com.google.android.material.chip.Chip(ctx)
                chip.text = t
                chip.isClickable = false
                chip.chipBackgroundColor = ctx.getColorStateList(R.color.mv_surface2)
                chip.setTextColor(ctx.getColor(R.color.mv_text_secondary))
                chip.textSize = 10f
                chips.addView(chip)
            }
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick?.invoke(item)
                onLongClick != null
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.path == b.path
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a.raw.toString() == b.raw.toString()
    }
}