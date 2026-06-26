package com.mediavault.playback

import android.net.Uri
import com.mediavault.data.MediaItem

object PlaylistBuilder {
    data class Episode(
        val path: String,
        val uri: Uri,
        val title: String,
        val subtitles: List<String>,
    )

    fun collectionKey(item: MediaItem): String {
        val key = item.raw.optString("collection_key_name", "").trim()
        if (key.isNotBlank()) return key
        if (item.collection.isNotBlank()) return item.collection
        return item.collectionKey()
    }

    fun groupByCollection(items: List<MediaItem>): Map<String, List<MediaItem>> =
        items.groupBy { collectionKey(it) }

    fun sortEpisodes(items: List<MediaItem>): List<MediaItem> =
        items.sortedWith(
            compareBy<MediaItem> { it.raw.optString("season", "").padStart(4, '0') }
                .thenBy { it.raw.optString("episode", "").padStart(4, '0') }
                .thenBy { it.path },
        )

    fun buildPlaylist(all: List<MediaItem>, currentPath: String, store: com.mediavault.data.MediaStore): Pair<List<Episode>, Int> {
        val current = all.find { it.path == currentPath } ?: return emptyList<Episode>() to -1
        val group = sortEpisodes(groupByCollection(all)[collectionKey(current)] ?: listOf(current))
        val episodes = group.mapNotNull { item ->
            val uri = resolveUri(item, store) ?: return@mapNotNull null
            val subs = mutableListOf<String>()
            val arr = item.raw.optJSONArray("subtitles")
            if (arr != null) {
                for (i in 0 until arr.length()) subs.add(arr.optString(i))
            }
            Episode(item.path, uri, item.displayTitle(), subs)
        }
        val index = episodes.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
        return episodes to index
    }

    fun resolveUri(item: MediaItem, store: com.mediavault.data.MediaStore): Uri? {
        val path = item.path
        if (path.startsWith("content://")) return Uri.parse(path)
        return MediaPlayback.resolvePlayUri(store, item)
    }
}