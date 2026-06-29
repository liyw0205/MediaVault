package com.mediavault.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.MediaStore
import com.mediavault.remote.RemotePath

sealed class ScrapeRootRow {
    abstract val key: String
    abstract val title: String
    abstract val subtitle: String

    data class Local(val uri: String, override val title: String, override val subtitle: String) : ScrapeRootRow() {
        override val key: String get() = uri
    }

    data class Remote(val remoteId: String, override val title: String, override val subtitle: String) : ScrapeRootRow() {
        override val key: String get() = remoteId
    }
}

class ScrapeRootAdapter(
    private val store: MediaStore,
    private val onIncrementalLocal: (String) -> Unit,
    private val onRescanLocal: (String) -> Unit,
    private val onRemoveLocal: (String, Int) -> Unit,
    private val onIncrementalRemote: (String) -> Unit,
    private val onRescanRemote: (String) -> Unit,
    private val onRemoveRemote: (String, Int) -> Unit,
    private val itemCountForLocal: (String) -> Int,
    private val itemCountForRemote: (String) -> Int,
) : ListAdapter<ScrapeRootRow, ScrapeRootAdapter.VH>(object : DiffUtil.ItemCallback<ScrapeRootRow>() {
    override fun areItemsTheSame(a: ScrapeRootRow, b: ScrapeRootRow) = a.key == b.key
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
        when (row) {
            is ScrapeRootRow.Local -> {
                h.scrape.setOnClickListener { onIncrementalLocal(row.uri) }
                h.rescan.setOnClickListener { onRescanLocal(row.uri) }
                h.remove.setOnClickListener { onRemoveLocal(row.uri, itemCountForLocal(row.uri)) }
            }
            is ScrapeRootRow.Remote -> {
                h.scrape.setOnClickListener { onIncrementalRemote(row.remoteId) }
                h.rescan.setOnClickListener { onRescanRemote(row.remoteId) }
                h.remove.setOnClickListener { onRemoveRemote(row.remoteId, itemCountForRemote(row.remoteId)) }
            }
        }
    }

    companion object {
        fun rowsFor(store: MediaStore, ctx: android.content.Context): List<ScrapeRootRow> {
            val out = mutableListOf<ScrapeRootRow>()
            for (uri in store.readLocalRootUris()) {
                val title = runCatching { store.readNfoTitleFromUri(Uri.parse(uri)) }.getOrDefault("")
                    .ifBlank { Uri.parse(uri).lastPathSegment ?: uri.takeLast(32) }
                out.add(ScrapeRootRow.Local(uri, title, uri))
            }
            for (r in store.readRemotesList()) {
                val sub = "${r.type.uppercase()} ${r.host}:${r.port} ${r.basePath}"
                out.add(ScrapeRootRow.Remote(r.id, r.name.ifBlank { r.host }, sub))
            }
            return out
        }
    }
}