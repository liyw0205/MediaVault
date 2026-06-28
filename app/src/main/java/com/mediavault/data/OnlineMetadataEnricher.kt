package com.mediavault.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object OnlineMetadataEnricher {

    fun enrichIfEnabled(
        context: Context,
        store: MediaStore,
        cfg: ScrapeSettings,
        fileName: String,
        path: String,
        draft: JSONObject,
        existingCoverLocal: String?,
    ): JSONObject {
        if (!cfg.isOnlineMode()) return draft
        val key = cfg.tmdbApiKey.trim()
        if (key.isBlank()) return draft

        val season = draft.optString("season", "").trim()
        val episode = draft.optString("episode", "").trim()
        val year = draft.optString("year", "").trim()
        val match = runCatching {
            TmdbClient.lookup(key, fileName, season, episode, year)
        }.getOrNull() ?: return draft

        val o = JSONObject(draft.toString())

        val isTvEpisode = match.mediaType == "tv" && match.episodeTitle.isNotBlank()
        val displayTitle = if (isTvEpisode) match.episodeTitle else match.title
        val displayPlot = if (isTvEpisode && match.episodePlot.isNotBlank()) match.episodePlot else match.plot

        if (o.optString("title", "").isBlank() || looksLikeFilename(o.optString("title", ""), fileName)) {
            if (displayTitle.isNotBlank()) o.put("title", displayTitle)
        }
        if (o.optString("title_cn", "").isBlank() && displayTitle.isNotBlank()) {
            o.put("title_cn", displayTitle)
        }
        if (o.optString("originaltitle", "").isBlank() && match.originalTitle.isNotBlank()) {
            o.put("originaltitle", match.originalTitle)
        }
        if (o.optString("show_title", "").isBlank() && match.title.isNotBlank() && isTvEpisode) {
            o.put("show_title", match.title)
        }
        if (o.optString("year", "").isBlank() && match.year.isNotBlank()) {
            o.put("year", match.year)
        }
        if (o.optString("plot", "").isBlank() && displayPlot.isNotBlank()) {
            o.put("plot", displayPlot)
        }
        if (isTvEpisode && match.episodeAirDate.isNotBlank() && o.optString("aired", "").isBlank()) {
            o.put("aired", match.episodeAirDate)
        }
        if (match.season.isNotBlank() && o.optString("season", "").isBlank()) {
            o.put("season", match.season)
        }
        if (match.episode.isNotBlank() && o.optString("episode", "").isBlank()) {
            o.put("episode", match.episode)
        }

        val genres = mutableListOf<String>()
        val ga = o.optJSONArray("genres")
        if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optString(i))
        if (genres.isEmpty() && match.genres.isNotEmpty()) {
            o.put("genres", JSONArray().also { arr -> match.genres.forEach { arr.put(it) } })
        }

        if (match.collectionName.isNotBlank()) {
            val coll = CollectionNames.sanitize(match.collectionName)
            if (o.optString("collection", "").isBlank() || o.optString("collection_key_name", "").isBlank()) {
                o.put("collection", coll)
                o.put("collection_key_name", coll)
            }
        }

        o.put("tmdb_id", match.tmdbId)
        o.put("tmdb_type", match.mediaType)
        o.put("metadata_source", "tmdb")

        if (existingCoverLocal.isNullOrBlank()) {
            val artUrl = match.episodeStillUrl?.takeIf { it.isNotBlank() } ?: match.posterUrl
            if (!artUrl.isNullOrBlank()) {
                val dest = File(store.coversDir, "tmdb_${sha1(path)}.jpg")
                if (!dest.isFile || dest.length() < 512) {
                    TmdbClient.downloadPoster(artUrl, dest)
                }
                if (dest.isFile && dest.length() >= 512) {
                    o.put("cover_local", dest.absolutePath)
                    o.put("cover_source", "tmdb")
                }
            }
        }

        return o
    }

    private fun looksLikeFilename(title: String, fileName: String): Boolean {
        val clean = fileName.substringBeforeLast('.')
        return title.equals(clean, ignoreCase = true)
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}