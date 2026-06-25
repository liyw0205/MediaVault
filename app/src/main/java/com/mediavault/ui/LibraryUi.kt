package com.mediavault.ui

import com.mediavault.data.MediaItem
import com.mediavault.playback.PlaylistBuilder
import kotlin.random.Random

object LibraryUi {
    const val PAGE_SIZE = 24

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
            val title = list.firstOrNull()?.collection?.takeIf { it.isNotBlank() } ?: key
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
        return items.shuffled(rnd).take(PAGE_SIZE)
    }

    fun historyItems(all: List<MediaItem>, paths: List<String>): List<MediaItem> {
        val map = all.associateBy { it.path }
        return paths.mapNotNull { map[it] }
    }

    fun search(items: List<MediaItem>, query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return items.filter { it.searchBlob().contains(q) }
    }

    fun matchedTags(items: List<MediaItem>, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val counts = linkedMapOf<String, Int>()
        for (it in items) {
            for (t in it.tags + it.genres) {
                if (t.lowercase().contains(q)) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(12).map { it.key }
    }

    fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        n < 1024 * 1024 * 1024 -> String.format("%.1f MB", n / (1024.0 * 1024))
        else -> String.format("%.2f GB", n / (1024.0 * 1024 * 1024))
    }
}