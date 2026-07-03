package com.mediavault.ui

import com.mediavault.data.MediaItem

object SearchOptions {
    enum class Sort { RecentPlayed, Modified, TitleAZ, YearDesc }
    enum class Source { All, Local, Remote }
    enum class Type { All, Tv, Movie }
    enum class WatchState { All, Unwatched, Watching, Watched }

    fun matchesSource(item: MediaItem, s: Source): Boolean = when (s) {
        Source.All -> true
        Source.Remote -> isRemote(item)
        Source.Local -> !isRemote(item)
    }

    fun matchesType(item: MediaItem, t: Type): Boolean = when (t) {
        Type.All -> true
        Type.Tv -> isTv(item)
        Type.Movie -> !isTv(item)
    }

    fun matchesWatchState(item: MediaItem, state: WatchState, hasProgress: Boolean, inHistory: Boolean): Boolean =
        when (state) {
            WatchState.All -> true
            WatchState.Unwatched -> !hasProgress && !inHistory
            WatchState.Watching -> hasProgress
            WatchState.Watched -> inHistory && !hasProgress
        }

    private fun isRemote(item: MediaItem): Boolean {
        val p = item.path
        return p.startsWith("neribox-remote") || p.startsWith("remote|") ||
            p.startsWith("mediavault-remote:")
    }

    private fun isTv(item: MediaItem): Boolean {
        if (item.raw.optString("season", "").isNotBlank()) return true
        if (item.raw.optString("episode", "").isNotBlank()) return true
        if (item.raw.optString("tmdb_type", "") == "tv") return true
        return false
    }

    fun sortInPlace(
        list: MutableList<MediaItem>,
        s: Sort,
        progressUpdatedAt: (MediaItem) -> Long? = { null },
        historyIndex: (MediaItem) -> Int? = { null },
    ) {
        when (s) {
            Sort.RecentPlayed -> list.sortWith(
                compareBy<MediaItem> { historyIndex(it) ?: Int.MAX_VALUE }
                    .thenByDescending { progressUpdatedAt(it) ?: 0L }
                    .thenBy { it.displayTitle().lowercase() },
            )
            Sort.TitleAZ -> list.sortBy { it.displayTitle().lowercase() }
            Sort.YearDesc -> list.sortByDescending { (it.year.toIntOrNull() ?: -1) }
            Sort.Modified -> list.sortByDescending { it.modified }
        }
    }
}
