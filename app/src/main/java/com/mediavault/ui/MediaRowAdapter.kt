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

class MediaRowAdapter(
    private val onCoverClick: (MediaItem) -> Unit,
    private val onInfoClick: (MediaItem) -> Unit,
) : ListAdapter<MediaItem, MediaRowAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_row, parent, false)
        return VH(v, onCoverClick, onInfoClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        itemView: View,
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
            if (local != null && File(local).isFile) {
                cover.setImageBitmap(BitmapFactory.decodeFile(local))
                cover.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            } else {
                cover.setImageDrawable(null)
                cover.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
            }
            coverArea.setOnClickListener { onCoverClick(item) }
            infoArea.setOnClickListener { onInfoClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.path == b.path
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a.raw.toString() == b.raw.toString()
    }
}