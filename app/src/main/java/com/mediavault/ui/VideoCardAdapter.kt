package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.R
import com.mediavault.data.MediaItem

class VideoCardAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
) : ListAdapter<MediaItem, VideoCardAdapter.VH>(Diff) {

    private var coverW = 0
    private var coverH = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video_card, parent, false)
        if (coverW <= 0) {
            val dm = parent.resources.displayMetrics
            val span = if (parent.resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
            coverW = (dm.widthPixels / span).coerceAtLeast(120)
            coverH = (coverW * 120 / 180).coerceAtLeast(80)
        }
        return VH(v, scope, coverW, coverH, onCoverClick, onInfoClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    class VH(
        itemView: View,
        private val scope: LifecycleCoroutineScope,
        private val coverW: Int,
        private val coverH: Int,
        private val onCoverClick: (MediaItem) -> Unit,
        private val onInfoClick: (MediaItem) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.titleText)
        private val meta: TextView = itemView.findViewById(R.id.metaText)
        private val cover: ImageView = itemView.findViewById(R.id.coverImage)
        private val placeholder: TextView = itemView.findViewById(R.id.coverPlaceholder)
        private val coverArea: View = itemView.findViewById(R.id.coverClickArea)
        private val infoArea: View = itemView.findViewById(R.id.infoClickArea)
        private val tagsLine: TextView = itemView.findViewById(R.id.tagsLine)

        private var boundPath: String? = null

        fun bind(item: MediaItem) {
            boundPath = item.path
            title.text = item.displayTitle()
            val ep = item.episodeLabel()
            meta.text = ep
            meta.visibility = if (ep.isBlank()) View.GONE else View.VISIBLE

            val local = item.coverLocalPath()
            placeholder.visibility = if (local != null) View.GONE else View.VISIBLE
            cover.visibility = View.VISIBLE
            CoverThumbnailLoader.load(scope, cover, local, coverW, coverH)

            val tags = (item.tags + item.genres).distinct().take(4)
            if (tags.isEmpty()) {
                tagsLine.visibility = View.GONE
            } else {
                tagsLine.visibility = View.VISIBLE
                tagsLine.text = tags.joinToString(" · ")
            }

            coverArea.setOnClickListener { onCoverClick(item) }
            infoArea.setOnClickListener { onInfoClick(item) }
        }

        fun recycle() {
            CoverThumbnailLoader.cancel(cover)
            boundPath = null
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.path == b.path
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a.raw.toString() == b.raw.toString()
    }
}