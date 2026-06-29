package com.mediavault.ui

import com.mediavault.data.MediaItem

object SearchOptions {
    enum class Sort { Relevance, TitleAZ, YearDesc, YearAsc, Modified }
    enum class Source { All, Local, Remote }
    enum class Type { All, Tv, Movie }

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

    fun sortInPlace(list: MutableList<MediaItem>, s: Sort) {
        when (s) {
            Sort.Relevance -> Unit
            Sort.TitleAZ -> list.sortBy { it.displayTitle().lowercase() }
            Sort.YearDesc -> list.sortByDescending { (it.year.toIntOrNull() ?: -1) }
            Sort.YearAsc -> list.sortWith(compareBy { it.year.toIntOrNull() ?: Int.MAX_VALUE })
            Sort.Modified -> list.sortByDescending { it.modified }
        }
    }
}
