package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.R

class CollectionAdapter(
    private val onClick: (LibraryUi.CollectionGroup) -> Unit,
) : ListAdapter<LibraryUi.CollectionGroup, CollectionAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_collection, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, private val onClick: (LibraryUi.CollectionGroup) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.collectionTitle)
        private val count: TextView = itemView.findViewById(R.id.collectionCount)

        fun bind(g: LibraryUi.CollectionGroup) {
            title.text = g.title
            count.text = itemView.context.getString(R.string.collection_count, g.items.size)
            itemView.setOnClickListener { onClick(g) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryUi.CollectionGroup>() {
        override fun areItemsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) = a.key == b.key
        override fun areContentsTheSame(a: LibraryUi.CollectionGroup, b: LibraryUi.CollectionGroup) = a == b
    }
}