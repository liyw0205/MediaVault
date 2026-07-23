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
        }.getOrNull()
        val mgr = (context.applicationContext as? com.mediavault.MediaVaultApp)?.scrapeManager
        if (match == null) {
            mgr?.tmdbMisses = (mgr?.tmdbMisses ?: 0) + 1
            return draft
        }
        mgr?.tmdbHits = (mgr?.tmdbHits ?: 0) + 1

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
        if (match.confidence.isNotBlank()) o.put("tmdb_match_confidence", match.confidence)
        if (match.confidence.isNotBlank()) o.put("tmdb_match_reason", reasonForConfidence(match.confidence))
        o.put("tmdb_match_title", match.title)
        if (match.year.isNotBlank()) o.put("tmdb_match_year", match.year)

        if (existingCoverLocal.isNullOrBlank() || !CoverFileCache.isValidCoverFile(File(existingCoverLocal))) {
            val artUrl = match.episodeStillUrl?.takeIf { it.isNotBlank() } ?: match.posterUrl
            if (!artUrl.isNullOrBlank()) {
                val dest = File(store.coversDir, "tmdb_${sha1(path)}.jpg")
                val wasMissing = !CoverFileCache.isValidCoverFile(dest)
                if (wasMissing) {
                    TmdbClient.downloadPoster(artUrl, dest)
                }
                if (CoverFileCache.isValidCoverFile(dest)) {
                    o.put("cover_local", dest.absolutePath)
                    o.put("cover_source", "tmdb")
                    if (wasMissing) {
                        mgr?.coverAdded = (mgr?.coverAdded ?: 0) + 1
                    }
                }
            }
        }

        return o
    }

    /** 用户从重新匹配对话框选定 TMDB 结果后，写回单条条目（仅更新库 JSON）。 */
    fun applyManualMatch(
        context: Context,
        store: MediaStore,
        item: MediaItem,
        match: TmdbClient.Match,
    ): Result<MediaItem> = runCatching {
        val fileName = item.raw.optString("file_name", "").ifBlank {
            item.path.substringAfterLast('/').substringAfterLast('%')
        }
        val draft = JSONObject(item.raw.toString())
        val cover = item.raw.optString("cover_local", "").trim().takeIf { it.isNotBlank() }
        val enriched = applyMatchToDraft(context, store, fileName, item.path, draft, cover, match)
        MediaItem.fromJson(enriched)
    }

    private fun applyMatchToDraft(
        context: Context,
        store: MediaStore,
        fileName: String,
        path: String,
        draft: JSONObject,
        existingCoverLocal: String?,
        match: TmdbClient.Match,
    ): JSONObject {
        val o = JSONObject(draft.toString())
        val isTvEpisode = match.mediaType == "tv" && match.episodeTitle.isNotBlank()
        val displayTitle = if (isTvEpisode) match.episodeTitle else match.title
        val displayPlot = if (isTvEpisode && match.episodePlot.isNotBlank()) match.episodePlot else match.plot

        if (displayTitle.isNotBlank()) o.put("title", displayTitle)
        if (displayTitle.isNotBlank()) o.put("title_cn", displayTitle)
        if (match.originalTitle.isNotBlank()) o.put("originaltitle", match.originalTitle)
        if (match.title.isNotBlank() && isTvEpisode) o.put("show_title", match.title)
        if (match.year.isNotBlank()) o.put("year", match.year)
        if (displayPlot.isNotBlank()) o.put("plot", displayPlot)
        if (isTvEpisode && match.episodeAirDate.isNotBlank()) o.put("aired", match.episodeAirDate)
        if (match.season.isNotBlank()) o.put("season", match.season)
        if (match.episode.isNotBlank()) o.put("episode", match.episode)
        if (match.genres.isNotEmpty()) {
            o.put("genres", JSONArray().also { arr -> match.genres.forEach { arr.put(it) } })
        }
        if (match.collectionName.isNotBlank()) {
            val coll = CollectionNames.sanitize(match.collectionName)
            o.put("collection", coll)
            o.put("collection_key_name", coll)
        }
        o.put("tmdb_id", match.tmdbId)
        o.put("tmdb_type", match.mediaType)
        o.put("metadata_source", "tmdb")
        val confidence = match.confidence.ifBlank { "manual_pick" }
        o.put("tmdb_match_confidence", confidence)
        o.put("tmdb_match_reason", reasonForConfidence(confidence))
        o.put("tmdb_match_title", match.title)
        if (match.year.isNotBlank()) o.put("tmdb_match_year", match.year)

        val artUrl = match.episodeStillUrl?.takeIf { it.isNotBlank() } ?: match.posterUrl
        if (!artUrl.isNullOrBlank()) {
            val dest = File(store.coversDir, "tmdb_${sha1(path)}.jpg")
            TmdbClient.downloadPoster(artUrl, dest)
            if (CoverFileCache.isValidCoverFile(dest)) {
                o.put("cover_local", dest.absolutePath)
                o.put("cover_source", "tmdb")
            }
        } else if (!existingCoverLocal.isNullOrBlank() && CoverFileCache.isValidCoverFile(File(existingCoverLocal))) {
            o.put("cover_local", existingCoverLocal)
        }
        return o
    }

    private fun looksLikeFilename(title: String, fileName: String): Boolean {
        val clean = fileName.substringBeforeLast('.')
        return title.equals(clean, ignoreCase = true)
    }

    private fun reasonForConfidence(confidence: String): String =
        when (confidence.trim()) {
            "title_exact+year_match" -> "标题完全匹配且年份一致"
            "title_exact" -> "标题完全匹配"
            "year_match" -> "年份一致，按热度和票数排序选中"
            "popularity" -> "未命中标题或年份强条件，按热度和票数排序选中"
            "manual_pick" -> "用户手动选择候选"
            else -> confidence.trim()
        }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
