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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.mediavault.R
import com.mediavault.data.MediaItem

class MediaRowAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
    private val queueContains: ((MediaItem) -> Boolean)? = null,
    private val onQueueClick: ((MediaItem) -> Unit)? = null,
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
        return VH(v, scope, coverW, coverH, onCoverClick, onInfoClick, queueContains, onQueueClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    fun refreshQueueState() {
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
        private val queueContains: ((MediaItem) -> Boolean)?,
        private val onQueueClick: ((MediaItem) -> Unit)?,
    ) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as? MaterialCardView
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val meta: TextView = itemView.findViewById(R.id.rowMeta)
        private val cover: ImageView = itemView.findViewById(R.id.rowCover)
        private val placeholder: TextView = itemView.findViewById(R.id.rowCoverPlaceholder)
        private val coverArea: View = itemView.findViewById(R.id.rowCoverArea)
        private val infoArea: View = itemView.findViewById(R.id.rowInfoArea)
        private val queueBtn: MaterialButton = itemView.findViewById(R.id.rowQueueActionBtn)
        private var boundItem: MediaItem? = null

        init {
            if (HomeUiPrefs.useTvFusionUi(itemView.context) && card != null) {
                card.isFocusable = true
                card.isFocusableInTouchMode = true
                card.setOnClickListener { boundItem?.let { onInfoClick(it) } }
                val focusSync = View.OnFocusChangeListener { _, _ ->
                    itemView.post {
                        setCardFocused(
                            card.hasFocus() ||
                                coverArea.hasFocus() ||
                                infoArea.hasFocus() ||
                                queueBtn.hasFocus(),
                        )
                    }
                }
                card.setOnFocusChangeListener(focusSync)
                coverArea.setOnFocusChangeListener(focusSync)
                infoArea.setOnFocusChangeListener(focusSync)
                queueBtn.setOnFocusChangeListener(focusSync)
                card.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        boundItem?.let { onInfoClick(it) }
                        true
                    } else {
                        false
                    }
                }
            }
        }

        private fun setCardFocused(hasFocus: Boolean) {
            val c = card ?: return
            val px = (if (hasFocus) 3 else 1) * itemView.resources.displayMetrics.density
            c.strokeWidth = px.toInt().coerceAtLeast(1)
            c.setStrokeColor(itemView.context.getColor(if (hasFocus) R.color.mv_primary else R.color.mv_line))
        }

        fun bind(item: MediaItem) {
            boundItem = item
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
            bindQueueButton(item)
        }

        private fun bindQueueButton(item: MediaItem) {
            val click = onQueueClick
            val contains = queueContains
            if (click == null || contains == null) {
                queueBtn.visibility = View.GONE
                queueBtn.setOnClickListener(null)
                return
            }
            val inQueue = contains(item)
            queueBtn.visibility = View.VISIBLE
            queueBtn.setText(if (inQueue) R.string.watch_queue_remove else R.string.watch_queue_add)
            queueBtn.setOnClickListener { click(item) }
        }

        fun recycle() {
            CoverThumbnailLoader.cancel(cover)
            boundItem = null
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
