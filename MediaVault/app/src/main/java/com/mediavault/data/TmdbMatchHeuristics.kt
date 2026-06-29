package com.mediavault.data

/** TMDB 自动匹配置信度：仅按热度选最优时视为低置信，建议在详情中重新匹配。 */
object TmdbMatchHeuristics {
    fun isWeakConfidence(confidence: String): Boolean =
        confidence.trim() == "popularity"

    fun isWeakTmdbMatch(item: MediaItem): Boolean {
        if (item.raw.optString("metadata_source", "").trim() != "tmdb") return false
        return isWeakConfidence(item.raw.optString("tmdb_match_confidence", ""))
    }

    fun weakTmdbItems(items: List<MediaItem>): List<MediaItem> =
        items.filter { isWeakTmdbMatch(it) }
            .sortedBy { it.displayTitle().lowercase() }
}