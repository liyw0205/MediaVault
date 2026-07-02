package com.mediavault.data

import com.mediavault.remote.RemotePath
import org.json.JSONArray
import org.json.JSONObject

data class ScrapeEvidence(
    val sourceType: String,
    val sourcePath: String,
    val fileName: String,
    val parsedTitle: String,
    val year: String,
    val season: String,
    val episode: String,
    val nfoHit: Boolean,
    val nfoTitle: String,
    val metadataSource: String,
    val tmdbId: String,
    val tmdbType: String,
    val tmdbTitle: String,
    val tmdbYear: String,
    val tmdbConfidence: String,
    val tmdbReason: String,
    val coverSource: String,
    val coverLocal: String,
    val sidecarFiles: List<String>,
    val subtitles: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sourceType", sourceType)
        put("sourcePath", sourcePath)
        put("fileName", fileName)
        put("parsedTitle", parsedTitle)
        put("year", year)
        put("season", season)
        put("episode", episode)
        put("nfoHit", nfoHit)
        put("nfoTitle", nfoTitle)
        put("metadataSource", metadataSource)
        put("tmdbId", tmdbId)
        put("tmdbType", tmdbType)
        put("tmdbTitle", tmdbTitle)
        put("tmdbYear", tmdbYear)
        put("tmdbConfidence", tmdbConfidence)
        put("tmdbReason", tmdbReason)
        put("coverSource", coverSource)
        put("coverLocal", coverLocal)
        put("sidecarFiles", JSONArray().also { arr -> sidecarFiles.forEach { arr.put(it) } })
        put("subtitles", JSONArray().also { arr -> subtitles.forEach { arr.put(it) } })
    }

    companion object {
        fun fromItem(item: MediaItem): ScrapeEvidence {
            val raw = item.raw
            val nfo = raw.optJSONObject("nfo")
            val remote = RemotePath.parse(item.path)
            val sourceType = when {
                remote != null -> raw.optString("remote_type", "").ifBlank { "remote" }
                item.path.startsWith("content://") -> "local"
                else -> "file"
            }
            return ScrapeEvidence(
                sourceType = sourceType,
                sourcePath = item.path,
                fileName = raw.optString("file_name", "").ifBlank { item.path.substringAfterLast('/') },
                parsedTitle = raw.optString("clean_title", "").ifBlank { item.cleanTitle },
                year = item.year,
                season = raw.optString("season", "").trim(),
                episode = raw.optString("episode", "").trim(),
                nfoHit = nfo != null && nfo.length() > 0,
                nfoTitle = nfo?.optString("title", "")?.trim().orEmpty(),
                metadataSource = raw.optString("metadata_source", "").trim(),
                tmdbId = raw.optString("tmdb_id", raw.optString("tmdbId", "")).trim(),
                tmdbType = raw.optString("tmdb_type", "").trim(),
                tmdbTitle = raw.optString("tmdb_match_title", "").trim(),
                tmdbYear = raw.optString("tmdb_match_year", "").trim(),
                tmdbConfidence = raw.optString("tmdb_match_confidence", "").trim(),
                tmdbReason = raw.optString("tmdb_match_reason", "").trim()
                    .ifBlank { raw.optString("tmdb_match_confidence", "").trim() },
                coverSource = raw.optString("cover_source", "").trim(),
                coverLocal = raw.optString("cover_local", "").trim(),
                sidecarFiles = raw.optJSONArray("sidecar_files").toStringList(),
                subtitles = raw.optJSONArray("subtitles").toStringList(),
            )
        }

        fun fromJson(o: JSONObject): ScrapeEvidence = ScrapeEvidence(
            sourceType = o.optString("sourceType", ""),
            sourcePath = o.optString("sourcePath", ""),
            fileName = o.optString("fileName", ""),
            parsedTitle = o.optString("parsedTitle", ""),
            year = o.optString("year", ""),
            season = o.optString("season", ""),
            episode = o.optString("episode", ""),
            nfoHit = o.optBoolean("nfoHit", false),
            nfoTitle = o.optString("nfoTitle", ""),
            metadataSource = o.optString("metadataSource", ""),
            tmdbId = o.optString("tmdbId", ""),
            tmdbType = o.optString("tmdbType", ""),
            tmdbTitle = o.optString("tmdbTitle", ""),
            tmdbYear = o.optString("tmdbYear", ""),
            tmdbConfidence = o.optString("tmdbConfidence", ""),
            tmdbReason = o.optString("tmdbReason", ""),
            coverSource = o.optString("coverSource", ""),
            coverLocal = o.optString("coverLocal", ""),
            sidecarFiles = o.optJSONArray("sidecarFiles").toStringList(),
            subtitles = o.optJSONArray("subtitles").toStringList(),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        optString(i, "").trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
    }
    return out
}
