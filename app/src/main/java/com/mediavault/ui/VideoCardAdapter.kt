package com.mediavault.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore

class VideoCardAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
    private val progressStore: PlaybackProgressStore? = null,
    private val sidebarKind: FusionUiMetrics.SidebarKind = FusionUiMetrics.SidebarKind.Home,
) : ListAdapter<MediaItem, VideoCardAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video_card, parent, false)
        val ctx = parent.context
        val fusion = HomeUiPrefs.useTvFusionUi(ctx)
        val coverW = FusionUiMetrics.videoCardCellWidthPx(ctx, sidebarKind)
        val coverH = FusionUiMetrics.videoCardCoverHeightPx(ctx, coverW)
        return VH(v, scope, coverW, coverH, fusion, onCoverClick, onInfoClick, progressStore)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

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
        private val tvFocus: Boolean,
        private val onCoverClick: (MediaItem) -> Unit,
        private val onInfoClick: (MediaItem) -> Unit,
        private val progressStore: PlaybackProgressStore?,
    ) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as? MaterialCardView
        private val title: TextView = itemView.findViewById(R.id.titleText)
        private val meta: TextView = itemView.findViewById(R.id.metaText)
        private val cover: ImageView = itemView.findViewById(R.id.coverImage)
        private val placeholder: TextView = itemView.findViewById(R.id.coverPlaceholder)
        private val coverArea: View = itemView.findViewById(R.id.coverClickArea)
        private val infoArea: View = itemView.findViewById(R.id.infoClickArea)
        private val tagsLine: TextView = itemView.findViewById(R.id.tagsLine)
        private val resumeBar: LinearProgressIndicator = itemView.findViewById(R.id.resumeProgress)

        private var boundPath: String? = null
        private var boundItem: MediaItem? = null

        init {
            if (tvFocus && card != null) {
                card.isFocusable = true
                card.isFocusableInTouchMode = true
                card.setOnFocusChangeListener { _, has ->
                    val px = (if (has) 3 else 1) * itemView.resources.displayMetrics.density
                    card.strokeWidth = px.toInt().coerceAtLeast(1)
                }
                card.setOnKeyListener { _, key, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                        boundItem?.let { onCoverClick(it) }
                        true
                    } else false
                }
            }
        }

        fun bind(item: MediaItem) {
            boundPath = item.path
            boundItem = item
            val fusion = HomeUiPrefs.useTvFusionUi(itemView.context)
            val coverLp = coverArea.layoutParams
            if (fusion) {
                coverLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                coverLp.height = coverH
                coverArea.layoutParams = coverLp
                cover.scaleType = ImageView.ScaleType.FIT_CENTER
            } else if (coverLp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                val px = (120 * itemView.resources.displayMetrics.density).toInt()
                coverLp.height = px
                coverArea.layoutParams = coverLp
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
            }
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
            boundItem = null
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
