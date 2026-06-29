package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.data.OnlineMetadataEnricher
import com.mediavault.data.ScrapeConfig
import com.mediavault.data.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TmdbRematchDialog {

    fun show(activity: AppCompatActivity, item: MediaItem, onDone: () -> Unit) {
        val cfg = ScrapeConfig.readSettings(activity)
        val apiKey = cfg.tmdbApiKey.trim()
        if (apiKey.isBlank()) {
            Toast.makeText(activity, R.string.tmdb_rematch_need_key, Toast.LENGTH_LONG).show()
            return
        }

        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_tmdb_rematch, null, false)
        val titleEt = root.findViewById<TextInputEditText>(R.id.tmdbRematchTitle)
        val yearEt = root.findViewById<TextInputEditText>(R.id.tmdbRematchYear)
        val seasonEt = root.findViewById<TextInputEditText>(R.id.tmdbRematchSeason)
        val episodeEt = root.findViewById<TextInputEditText>(R.id.tmdbRematchEpisode)
        val searchBtn = root.findViewById<View>(R.id.tmdbRematchSearchBtn)
        val statusTv = root.findViewById<TextView>(R.id.tmdbRematchStatus)
        val resultsRv = root.findViewById<RecyclerView>(R.id.tmdbRematchResults)

        titleEt.setText(
            item.raw.optString("tmdb_match_title", "").trim()
                .ifBlank { item.raw.optString("show_title", "").trim() }
                .ifBlank { item.displayTitle() },
        )
        yearEt.setText(
            item.raw.optString("tmdb_match_year", "").trim()
                .ifBlank { item.year },
        )
        seasonEt.setText(item.raw.optString("season", "").trim())
        episodeEt.setText(item.raw.optString("episode", "").trim())

        resultsRv.layoutManager = LinearLayoutManager(activity)
        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        fun labelFor(m: TmdbClient.Match): String {
            val name = when {
                m.mediaType == "tv" && m.episodeTitle.isNotBlank() -> "${m.title} · ${m.episodeTitle}"
                else -> m.title
            }
            val yr = m.year.takeIf { it.isNotBlank() } ?: "?"
            val kind = if (m.mediaType == "tv") activity.getString(R.string.tmdb_rematch_kind_tv)
            else activity.getString(R.string.tmdb_rematch_kind_movie)
            return activity.getString(R.string.tmdb_rematch_pick_fmt, kind, name, yr)
        }

        fun applyMatch(match: TmdbClient.Match) {
            val app = activity.application as MediaVaultApp
            val repo = app.repository
            val store = repo.store
            activity.lifecycleScope.launch {
                statusTv.visibility = View.VISIBLE
                statusTv.text = activity.getString(R.string.tmdb_rematch_applying)
                val result = withContext(Dispatchers.IO) {
                    OnlineMetadataEnricher.applyManualMatch(activity, store, item, match)
                }
                result.fold(
                    onSuccess = { updated ->
                        withContext(Dispatchers.IO) {
                            repo.appendSingleContentItem(updated)
                        }
                        Toast.makeText(activity, R.string.tmdb_rematch_apply_done, Toast.LENGTH_SHORT).show()
                        dialogRef?.dismiss()
                        onDone()
                    },
                    onFailure = { e ->
                        statusTv.text = activity.getString(R.string.tmdb_rematch_apply_fail, e.message ?: "?")
                    },
                )
            }
        }

        searchBtn.setOnClickListener {
            val q = titleEt.text?.toString()?.trim().orEmpty()
            if (q.length < 2) {
                Toast.makeText(activity, R.string.tmdb_rematch_field_title, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val year = yearEt.text?.toString()?.trim().orEmpty()
            val season = seasonEt.text?.toString()?.trim().orEmpty()
            val episode = episodeEt.text?.toString()?.trim().orEmpty()
            statusTv.visibility = View.VISIBLE
            statusTv.text = activity.getString(R.string.tmdb_rematch_searching)
            resultsRv.visibility = View.GONE
            searchBtn.isEnabled = false
            activity.lifecycleScope.launch {
                val list = withContext(Dispatchers.IO) {
                    TmdbClient.searchCandidates(apiKey, q, year, season, episode, 3)
                }
                searchBtn.isEnabled = true
                if (list.isEmpty()) {
                    statusTv.text = activity.getString(R.string.tmdb_rematch_no_results)
                    resultsRv.visibility = View.GONE
                    return@launch
                }
                statusTv.visibility = View.GONE
                resultsRv.visibility = View.VISIBLE
                resultsRv.adapter = object : RecyclerView.Adapter<CandidateVH>() {
                    override fun getItemCount() = list.size
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): CandidateVH {
                        val v = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_tmdb_candidate, parent, false)
                        return CandidateVH(v)
                    }
                    override fun onBindViewHolder(holder: CandidateVH, position: Int) {
                        val m = list[position]
                        holder.line.text = labelFor(m)
                        holder.itemView.setOnClickListener { applyMatch(m) }
                    }
                }
            }
        }

        val builder = MvDialog.builder(activity)
            .setTitle(R.string.tmdb_rematch_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
        dialogRef = MvDialog.showStyled(builder, root)
    }

    private class CandidateVH(v: View) : RecyclerView.ViewHolder(v) {
        val line: TextView = v.findViewById(R.id.tmdbCandidateLine)
    }
}