package com.mediavault.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.MediaStore

data class ScrapeRootRow(val uri: String, val title: String, val subtitle: String)

class ScrapeRootAdapter(
    private val store: MediaStore,
    private val onIncremental: (String) -> Unit,
    private val onRescan: (String) -> Unit,
    private val onRemove: (String, Int) -> Unit,
    private val itemCountForRoot: (String) -> Int,
) : ListAdapter<ScrapeRootRow, ScrapeRootAdapter.VH>(object : DiffUtil.ItemCallback<ScrapeRootRow>() {
    override fun areItemsTheSame(a: ScrapeRootRow, b: ScrapeRootRow) = a.uri == b.uri
    override fun areContentsTheSame(a: ScrapeRootRow, b: ScrapeRootRow) = a == b
}) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rootTitle)
        val subtitle: TextView = v.findViewById(R.id.rootSubtitle)
        val scrape: MaterialButton = v.findViewById(R.id.btnScrapeRoot)
        val rescan: MaterialButton = v.findViewById(R.id.btnRescrapeRoot)
        val remove: MaterialButton = v.findViewById(R.id.btnRemoveRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_scrape_root, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = getItem(position)
        h.title.text = row.title
        h.subtitle.text = row.subtitle
        h.scrape.setOnClickListener { onIncremental(row.uri) }
        h.rescan.setOnClickListener { onRescan(row.uri) }
        h.remove.setOnClickListener {
            val n = itemCountForRoot(row.uri)
            onRemove(row.uri, n)
        }
    }

    companion object {
        fun rowsFor(store: MediaStore, ctx: android.content.Context): List<ScrapeRootRow> {
            val uris = store.readLocalRootUris()
            if (uris.isEmpty()) return emptyList()
            return uris.map { uri ->
                val title = runCatching { store.readNfoTitleFromUri(Uri.parse(uri)) }.getOrDefault("")
                    .ifBlank { Uri.parse(uri).lastPathSegment ?: uri.takeLast(32) }
                ScrapeRootRow(uri, title, uri)
            }
        }
    }
}