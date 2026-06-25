package com.mediavault.data

import org.json.JSONArray
import org.json.JSONObject

data class MediaItem(
    val path: String,
    val title: String,
    val cleanTitle: String,
    val tags: List<String>,
    val genres: List<String>,
    val year: String,
    val size: Long,
    val modified: String,
    val nfoTitle: String,
    val plot: String,
    val collection: String,
    val raw: JSONObject,
) {
    fun displayTitle(): String {
        val titleCn = raw.optString("title_cn", "").trim()
        if (titleCn.isNotBlank()) return titleCn
        if (nfoTitle.isNotBlank()) return nfoTitle
        if (title.isNotBlank()) return title
        if (cleanTitle.isNotBlank()) return cleanTitle
        return path.substringAfterLast('/')
    }

    fun collectionKey(): String {
        val key = raw.optString("collection_key_name", "").trim()
        if (key.isNotBlank()) return key
        if (collection.isNotBlank()) return collection
        val parent = path.substringBeforeLast('/')
        return parent.substringAfterLast('/').ifBlank { "未分组" }
    }

    fun coverLocalPath(): String? = raw.optString("cover_local", "").trim().takeIf { it.isNotBlank() }

    fun episodeLabel(): String {
        val s = raw.optString("season", "").trim()
        val e = raw.optString("episode", "").trim()
        return when {
            s.isNotBlank() && e.isNotBlank() -> "S${s.padStart(2, '0')}E${e.padStart(2, '0')}"
            e.isNotBlank() -> "第 $e 集"
            else -> ""
        }
    }

    fun rootKey(): String {
        val p = path
        if (p.startsWith("neribox-remote") || p.startsWith("remote|")) return "远程"
        if (p.startsWith("content://")) return "本地(SAF)"
        val idx = p.indexOf('/', 1)
        return if (idx > 0) p.substring(0, idx) else p.substringBeforeLast('/').ifBlank { "本地" }
    }

    fun searchBlob(): String = buildString {
        append(displayTitle())
        append(' ')
        append(raw.optString("title_cn", ""))
        append(' ')
        append(raw.optString("originaltitle", ""))
        append(' ')
        append(plot)
        append(' ')
        append(path)
        append(' ')
        append(tags.joinToString(" "))
        append(' ')
        append(genres.joinToString(" "))
    }.lowercase()

    companion object {
        fun fromJson(o: JSONObject): MediaItem {
            val tags = mutableListOf<String>()
            val ta = o.optJSONArray("tags")
            if (ta != null) for (i in 0 until ta.length()) tags.add(ta.optString(i))
            val genres = mutableListOf<String>()
            val ga = o.optJSONArray("genres")
            if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optString(i))
            val nfo = o.optJSONObject("nfo") ?: JSONObject()
            return MediaItem(
                path = o.optString("path", ""),
                title = o.optString("title", ""),
                cleanTitle = o.optString("clean_title", ""),
                tags = tags,
                genres = genres,
                year = o.optString("year", ""),
                size = o.optLong("size", 0L),
                modified = o.optString("modified", ""),
                nfoTitle = nfo.optString("title", ""),
                plot = o.optString("plot", "").ifBlank { nfo.optString("plot", "") },
                collection = o.optString("collection", ""),
                raw = o,
            )
        }
    }
}

data class MediaLibrary(
    val ok: Boolean,
    val items: List<MediaItem>,
    val sourcePath: String,
) {
    companion object {
        fun parse(text: String, sourcePath: String): MediaLibrary {
            val root = JSONObject(text)
            val ok = root.optBoolean("ok", true)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<MediaItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(MediaItem.fromJson(o))
            }
            return MediaLibrary(ok, items, sourcePath)
        }
    }
}