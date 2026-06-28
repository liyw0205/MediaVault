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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore

class VideoCardAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
    private val progressStore: PlaybackProgressStore? = null,
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
        return VH(v, scope, coverW, coverH, onCoverClick, onInfoClick, progressStore)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    /** 从播放器返回后刷新封面续播条 */
    fun refreshProgressHints() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

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
        private val progressStore: PlaybackProgressStore?,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.titleText)
        private val meta: TextView = itemView.findViewById(R.id.metaText)
        private val cover: ImageView = itemView.findViewById(R.id.coverImage)
        private val placeholder: TextView = itemView.findViewById(R.id.coverPlaceholder)
        private val coverArea: View = itemView.findViewById(R.id.coverClickArea)
        private val infoArea: View = itemView.findViewById(R.id.infoClickArea)
        private val tagsLine: TextView = itemView.findViewById(R.id.tagsLine)
        private val resumeBar: LinearProgressIndicator = itemView.findViewById(R.id.resumeProgress)

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

            val frac = progressStore?.getFraction(item.path)
            if (frac != null && frac > 0.01f) {
                resumeBar.visibility = View.VISIBLE
                resumeBar.isIndeterminate = false
                resumeBar.max = 1000
                resumeBar.progress = (frac * 1000).toInt().coerceIn(1, 999)
            } else {
                resumeBar.visibility = View.GONE
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
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) =
            a.displayTitle() == b.displayTitle() &&
                a.coverLocalPath() == b.coverLocalPath() &&
                a.episodeLabel() == b.episodeLabel()
    }
}