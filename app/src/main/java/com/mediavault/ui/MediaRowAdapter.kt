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

class MediaRowAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
) : ListAdapter<MediaItem, MediaRowAdapter.VH>(Diff) {

    private var coverW = 0
    private var coverH = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_row, parent, false)
        if (coverW <= 0) {
            val dm = parent.resources.displayMetrics
            coverW = (120 * dm.density).toInt().coerceAtLeast(96)
            coverH = (72 * dm.density).toInt().coerceAtLeast(64)
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
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val meta: TextView = itemView.findViewById(R.id.rowMeta)
        private val cover: ImageView = itemView.findViewById(R.id.rowCover)
        private val placeholder: TextView = itemView.findViewById(R.id.rowCoverPlaceholder)
        private val coverArea: View = itemView.findViewById(R.id.rowCoverArea)
        private val infoArea: View = itemView.findViewById(R.id.rowInfoArea)

        fun bind(item: MediaItem) {
            title.text = item.displayTitle()
            val sub = LibraryUi.rowSubtitle(item)
            meta.text = sub
            meta.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
            val local = item.coverLocalPath()
            placeholder.visibility = if (local != null) View.GONE else View.VISIBLE
            cover.visibility = View.VISIBLE
            CoverThumbnailLoader.load(scope, cover, local, coverW, coverH)
            coverArea.setOnClickListener { onCoverClick(item) }
            infoArea.setOnClickListener { onInfoClick(item) }
        }

        fun recycle() {
            CoverThumbnailLoader.cancel(cover)
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.path == b.path
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) =
            a.displayTitle() == b.displayTitle() &&
                a.plot == b.plot &&
                a.coverLocalPath() == b.coverLocalPath()
    }
}