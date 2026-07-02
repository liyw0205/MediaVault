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
import com.mediavault.R

class CollectionAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onClick: (LibraryUi.CollectionGroup) -> Unit,
) : ListAdapter<LibraryUi.CollectionGroup, CollectionAdapter.VH>(Diff) {

    private var coverW = 0
    private var coverH = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collection_row, parent, false)
        val fusion = HomeUiPrefs.useTvFusionUi(parent.context)
        if (fusion) {
            v.isFocusable = true
            v.isFocusableInTouchMode = true
        }
        if (coverW <= 0) {
            val ctx = parent.context
            val fusion = HomeUiPrefs.useTvFusionUi(ctx)
            if (fusion) {
                val cell = FusionUiMetrics.listAreaWidthPx(ctx, FusionUiMetrics.SidebarKind.Collections) / 2
                coverW = (cell * 0.42f).toInt().coerceIn(96, 160)
                coverH = (coverW * 0.62f).toInt().coerceAtLeast(64)
            } else {
                val dm = parent.resources.displayMetrics
                coverW = (120 * dm.density).toInt().coerceAtLeast(96)
                coverH = (72 * dm.density).toInt().coerceAtLeast(64)
            }
        }
        return VH(v, scope, coverW, coverH, fusion, onClick)
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
        private val tvFocus: Boolean,
        private val onClick: (LibraryUi.CollectionGroup) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.collectionTitle)
        private val count: TextView = itemView.findViewById(R.id.collectionCount)
        private val cover: ImageView = itemView.findViewById(R.id.collectionCover)
        private val placeholder: TextView = itemView.findViewById(R.id.collectionCoverPlaceholder)
        private val chips: com.google.android.material.chip.ChipGroup =
            itemView.findViewById(R.id.collectionTagChips)

        fun bind(g: LibraryUi.CollectionGroup) {
            title.text = g.title
            count.text = itemView.context.getString(R.string.collection_count, g.items.size)
            val rep = g.items.firstOrNull()
            val local = rep?.coverLocalPath()
            placeholder.visibility = if (local != null) View.GONE else View.VISIBLE
            cover.visibility = View.VISIBLE
            CoverThumbnailLoader.load(scope, cover, local, coverW, coverH)
            chips.removeAllViews()
            val ctx = itemView.context
            val fusion = HomeUiPrefs.useTvFusionUi(ctx)
            FusionTagLayoutHelper.applyFusionChipGroup(chips, fusion)
            chips.isSingleLine = !fusion
            val tagLimit = if (fusion) 12 else 5
            val tags = g.items.flatMap { it.tags + it.genres }.distinct().take(tagLimit)
            for (t in tags) {
                val chip = com.google.android.material.chip.Chip(ctx)
                chip.text = t
                chip.isClickable = false
                chip.chipBackgroundColor = ctx.getColorStateList(R.color.mv_surface2)
                chip.setTextColor(ctx.getColor(R.color.mv_text_secondary))
                chip.textSize = if (fusion) 10f else 10f
                FusionTagLayoutHelper.styleTagChip(chip, fusion)
                chips.addView(chip)
            }
            itemView.setOnClickListener { onClick(g) }
            if (tvFocus) {
                itemView.setOnKeyListener { _, key, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                        onClick(g)
                        true
                    } else false
                }
            }
        }

        fun recycle() {
            CoverThumbnailLoader.cancel(cover)
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryUi.CollectionGroup>() {
        override fun areItemsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) = a.key == b.key
        override fun areContentsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) =
            a.title == b.title && a.items.size == b.items.size &&
                a.items.firstOrNull()?.coverLocalPath() == b.items.firstOrNull()?.coverLocalPath()
    }
}