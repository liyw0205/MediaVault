package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.data.TmdbMatchHeuristics

object WeakTmdbListDialog {

    fun show(activity: AppCompatActivity, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_weak_tmdb_list, null, false)
        val rv = root.findViewById<RecyclerView>(R.id.weakTmdbRecycler)
        rv.layoutManager = LinearLayoutManager(activity)
        var dialogRef: androidx.appcompat.app.AlertDialog? = null
        rv.adapter = object : RecyclerView.Adapter<LineVH>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LineVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_weak_tmdb, parent, false)
                return LineVH(v)
            }
            override fun onBindViewHolder(holder: LineVH, position: Int) {
                val item = items[position]
                val ep = item.episodeLabel()
                val title = item.displayTitle()
                holder.title.text = if (ep.isNotBlank()) "$title · $ep" else title
                holder.meta.text = buildString {
                    append("TMDB ")
                    append(item.raw.optString("tmdb_match_confidence", "").ifBlank { "?" })
                    val reason = item.raw.optString("tmdb_match_reason", "").trim()
                    if (reason.isNotBlank()) {
                        append(" · ")
                        append(reason)
                    }
                    append(" · ")
                    append(item.raw.optString("tmdb_match_title", "").ifBlank { "?" })
                    append(" · ")
                    append(item.path)
                }
                holder.open.setOnClickListener {
                    activity.startActivity(VideoDetailActivity.intent(activity, item.path))
                }
                holder.rematch.setOnClickListener {
                    TmdbRematchDialog.show(activity, item) {
                        dialogRef?.dismiss()
                    }
                }
            }
        }
        val builder = MvDialog.builder(activity)
            .setTitle(activity.getString(R.string.weak_tmdb_list_title, items.size))
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
        dialogRef = MvDialog.showStyled(builder, root)
    }

    fun showFromLibrary(activity: AppCompatActivity, items: List<MediaItem>) {
        show(activity, TmdbMatchHeuristics.weakTmdbItems(items))
    }

    private class LineVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.weakTmdbTitle)
        val meta: TextView = v.findViewById(R.id.weakTmdbMeta)
        val open: Button = v.findViewById(R.id.weakTmdbOpen)
        val rematch: Button = v.findViewById(R.id.weakTmdbRematch)
    }
}
