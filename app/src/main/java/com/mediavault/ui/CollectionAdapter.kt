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
import java.io.File

class CollectionAdapter(
    private val onClick: (LibraryUi.CollectionGroup) -> Unit,
) : ListAdapter<LibraryUi.CollectionGroup, CollectionAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collection_row, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, private val onClick: (LibraryUi.CollectionGroup) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.collectionTitle)
        private val count: TextView = itemView.findViewById(R.id.collectionCount)
        private val cover: ImageView = itemView.findViewById(R.id.collectionCover)
        private val placeholder: TextView = itemView.findViewById(R.id.collectionCoverPlaceholder)
        private val chips: com.google.android.material.chip.ChipGroup = itemView.findViewById(R.id.collectionTagChips)

        fun bind(g: LibraryUi.CollectionGroup) {
            title.text = g.title
            count.text = itemView.context.getString(R.string.collection_count, g.items.size)
            val rep = g.items.firstOrNull()
            val local = rep?.coverLocalPath()
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
            val ctx = itemView.context
            val tags = g.items.flatMap { it.tags + it.genres }.distinct().take(5)
            for (t in tags) {
                val chip = com.google.android.material.chip.Chip(ctx)
                chip.text = t
                chip.isClickable = false
                chip.chipBackgroundColor = ctx.getColorStateList(R.color.mv_surface2)
                chip.setTextColor(ctx.getColor(R.color.mv_text_secondary))
                chip.textSize = 10f
                chips.addView(chip)
            }
            itemView.setOnClickListener { onClick(g) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryUi.CollectionGroup>() {
        override fun areItemsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) = a.key == b.key
        override fun areContentsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) = a == b
    }
}