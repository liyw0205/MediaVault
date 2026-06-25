package com.mediavault.ui

import com.mediavault.data.CollectionNames
import com.mediavault.data.MediaItem
import com.mediavault.playback.PlaylistBuilder
import kotlin.random.Random

object LibraryUi {
    const val PAGE_SIZE = 20

    /** 行列表副标题：时长 + 简介（过长省略） */
    fun rowSubtitle(item: MediaItem, plotMaxChars: Int = 80): String {
        val runtime = item.raw.optString("runtime", "").trim()
            .ifBlank { item.raw.optJSONObject("nfo")?.optString("runtime", "")?.trim() ?: "" }
        val dur = formatRuntimeLabel(runtime)
        val plot = item.plot.trim()
        val plotShort = if (plot.length > plotMaxChars) plot.take(plotMaxChars) + "…" else plot
        return when {
            dur.isNotBlank() && plotShort.isNotBlank() -> "$dur · $plotShort"
            dur.isNotBlank() -> dur
            plotShort.isNotBlank() -> plotShort
            else -> ""
        }
    }

    private fun formatRuntimeLabel(raw: String): String {
        if (raw.isBlank()) return ""
        val mins = raw.toIntOrNull() ?: return raw
        if (mins <= 0) return raw
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    fun sanitizeCollectionName(raw: String): String = CollectionNames.sanitize(raw)

    fun collectionDisplayTitle(item: MediaItem, fallbackKey: String): String {
        val raw = item.raw.optString("set_name", "").trim()
            .ifBlank { item.collection.trim() }
            .ifBlank { fallbackKey }
        return sanitizeCollectionName(raw).ifBlank { fallbackKey }
    }

    fun distinctRoots(items: List<MediaItem>): List<String> {
        val set = linkedSetOf<String>()
        for (it in items) set.add(it.rootKey())
        return set.toList()
    }

    fun distinctCollections(items: List<MediaItem>): Int =
        items.map { PlaylistBuilder.collectionKey(it) }.distinct().size

    data class CollectionGroup(
        val key: String,
        val title: String,
        val items: List<MediaItem>,
    )

    fun collectionGroups(items: List<MediaItem>): List<CollectionGroup> {
        val map = items.groupBy { PlaylistBuilder.collectionKey(it) }
        return map.map { (key, list) ->
            val title = list.firstOrNull()?.let { collectionDisplayTitle(it, key) } ?: key
            CollectionGroup(key, title, PlaylistBuilder.sortEpisodes(list))
        }.sortedBy { it.title.lowercase() }
    }

    fun filterByRoot(items: List<MediaItem>, root: String?): List<MediaItem> {
        if (root.isNullOrBlank()) return items
        return items.filter { it.rootKey() == root }
    }

    fun recommend(items: List<MediaItem>, seed: Long): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        val rnd = Random(seed)
        return items.shuffled(rnd)
    }

    fun historyItems(all: List<MediaItem>, paths: List<String>): List<MediaItem> {
        val map = all.associateBy { it.path }
        return paths.mapNotNull { map[it] }
    }

    fun search(items: List<MediaItem>, query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return items
        return items.filter { it.searchBlob().contains(q) }
    }

    fun matchedTags(items: List<MediaItem>, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return allTags(items)
        val counts = linkedMapOf<String, Int>()
        for (it in items) {
            for (t in it.tags + it.genres) {
                if (t.lowercase().contains(q)) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(24).map { it.key }
    }

    fun allTags(items: List<MediaItem>): List<String> {
        val counts = linkedMapOf<String, Int>()
        for (it in items) {
            for (t in it.tags + it.genres) {
                if (t.isNotBlank()) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        n < 1024 * 1024 * 1024 -> String.format("%.1f MB", n / (1024.0 * 1024))
        else -> String.format("%.2f GB", n / (1024.0 * 1024 * 1024))
    }
}