package com.mediavault.ui

import com.mediavault.data.CollectionNames
import com.mediavault.data.MediaItem
import com.mediavault.data.WatchQueueStore
import com.mediavault.playback.PlaylistBuilder
import kotlin.math.abs
import kotlin.random.Random

object LibraryUi {
    const val PAGE_SIZE = 20
    /** 详情页「同合集」每页条数，减轻嵌套列表一次性绑定导致的掉帧 */
    const val RELATED_PAGE_SIZE = 10

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

    const val TAG_COLLECTION_PREFIX = "tag:"
    const val GENRE_COLLECTION_PREFIX = "genre:"

    fun collectionGroups(items: List<MediaItem>): List<CollectionGroup> {
        val map = items.groupBy { PlaylistBuilder.collectionKey(it) }
        return map.map { (key, list) ->
            val title = list.firstOrNull()?.let { collectionDisplayTitle(it, key) } ?: key
            CollectionGroup(key, title, PlaylistBuilder.sortEpisodes(list))
        }.sortedBy { it.title.lowercase() }
    }

    /** 与 [item] 同合集的全部条目（已按集数排序），不扫描整库分组表 */
    fun itemsInSameCollection(all: List<MediaItem>, item: MediaItem): List<MediaItem> {
        val key = PlaylistBuilder.collectionKey(item)
        val peers = all.filter { PlaylistBuilder.collectionKey(it) == key }
        return PlaylistBuilder.sortEpisodes(peers)
    }

    fun paginateItems(list: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val start = (page - 1) * pageSize
        if (start >= list.size) return emptyList()
        return list.subList(start, minOf(start + pageSize, list.size))
    }

    fun pageCount(listSize: Int, pageSize: Int): Int =
        if (listSize <= 0) 1 else (listSize + pageSize - 1) / pageSize

    fun paginateGroups(list: List<CollectionGroup>, page: Int, pageSize: Int): List<CollectionGroup> {
        val start = (page - 1) * pageSize
        if (start >= list.size) return emptyList()
        return list.subList(start, minOf(start + pageSize, list.size))
    }

    fun tagCollectionGroups(items: List<MediaItem>): List<CollectionGroup> {
        val map = linkedMapOf<String, MutableList<MediaItem>>()
        for (it in items) {
            for (t in it.tags) {
                val tag = t.trim()
                if (tag.isBlank()) continue
                map.getOrPut(tag) { mutableListOf() }.add(it)
            }
        }
        return map.map { (tag, list) ->
            CollectionGroup(
                key = TAG_COLLECTION_PREFIX + tag,
                title = tag,
                items = list.distinctBy { it.path }.sortedBy { it.displayTitle().lowercase() },
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun genreCollectionGroups(items: List<MediaItem>): List<CollectionGroup> {
        val map = linkedMapOf<String, MutableList<MediaItem>>()
        for (it in items) {
            for (g in it.genres) {
                val genre = g.trim()
                if (genre.isBlank()) continue
                map.getOrPut(genre) { mutableListOf() }.add(it)
            }
        }
        return map.map { (genre, list) ->
            CollectionGroup(
                key = GENRE_COLLECTION_PREFIX + genre,
                title = genre,
                items = list.distinctBy { it.path }.sortedBy { it.displayTitle().lowercase() },
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun resolveCollectionGroup(items: List<MediaItem>, key: String): CollectionGroup? {
        when {
            key.startsWith(TAG_COLLECTION_PREFIX) -> {
                val tag = key.removePrefix(TAG_COLLECTION_PREFIX)
                val list = items.filter { it.tags.any { t -> t.trim() == tag } }
                if (list.isEmpty()) return null
                return CollectionGroup(key, tag, list.sortedBy { it.displayTitle().lowercase() })
            }
            key.startsWith(GENRE_COLLECTION_PREFIX) -> {
                val genre = key.removePrefix(GENRE_COLLECTION_PREFIX)
                val list = items.filter { it.genres.any { g -> g.trim() == genre } }
                if (list.isEmpty()) return null
                return CollectionGroup(key, genre, list.sortedBy { it.displayTitle().lowercase() })
            }
            else -> return collectionGroups(items).find { it.key == key }
        }
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

    data class RecommendationResult(
        val items: List<MediaItem>,
        val summary: String,
        val reasons: Map<String, String>,
    )

    data class RecommendationReasonCount(
        val reason: String,
        val label: String,
        val count: Int,
    )

    fun explainableRecommendations(
        items: List<MediaItem>,
        historyPaths: List<String>,
        progressPaths: Set<String>,
        seed: Long,
        limit: Int,
        recentlyShownPaths: Collection<String> = emptyList(),
    ): RecommendationResult {
        if (items.isEmpty()) return RecommendationResult(emptyList(), "", emptyMap())
        val byPath = items.associateBy { it.path }
        val historyItems = historyPaths.mapNotNull { byPath[it] }.take(20)
        val historySet = historyPaths.toHashSet()
        val cooldown = recentlyShownPaths.toHashSet()
        val rnd = Random(seed)
        val candidates = linkedMapOf<String, RecommendationCandidate>()

        fun offer(item: MediaItem, reason: String, baseScore: Int) {
            if (item.path.isBlank()) return
            // 已推过的批次强降权；库耗尽时仍可作为兜底入选
            val cooled = if (item.path in cooldown) COOLDOWN_PENALTY else 0
            val score = baseScore + stableJitter(seed, item.path) - cooled
            val cur = candidates[item.path]
            if (cur == null || score > cur.score) {
                candidates[item.path] = RecommendationCandidate(item, reason, score)
            }
        }

        val collections = historyItems
            .map { PlaylistBuilder.collectionKey(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        collections.forEachIndexed { ci, key ->
            val episodes = PlaylistBuilder.sortEpisodes(
                items.filter { PlaylistBuilder.collectionKey(it) == key },
            ).filterNot { it.path in historySet || it.path in progressPaths }
            // 合集内用 seed 扰动顺序，避免总是推同一前几集
            val ordered = if (episodes.size <= 1) {
                episodes
            } else {
                val head = episodes.take(2)
                val rest = episodes.drop(2).shuffled(Random(seed xor key.hashCode().toLong()))
                head + rest
            }
            ordered.forEachIndexed { i, item ->
                offer(item, RECOMMEND_REASON_COLLECTION, 150 - ci * 8 - i.coerceAtMost(30))
            }
        }

        val tagAnchors = historyItems
            .flatMap { it.tags + it.genres }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)
        if (tagAnchors.isNotEmpty()) {
            val typeHits = items.asSequence()
                .filter { it.path !in historySet && it.path !in progressPaths }
                .mapNotNull { item ->
                    val matches = (item.tags + item.genres).count { it in tagAnchors }
                    if (matches > 0) item to matches else null
                }
                .toList()
                .shuffled(rnd)
            for ((item, matches) in typeHits) {
                offer(item, RECOMMEND_REASON_TYPE, 120 + matches * 5)
            }
        }

        val sortedRecent = items.sortedWith(
            compareByDescending<MediaItem> { it.modified }
                .thenBy { it.displayTitle().lowercase() },
        )
        val notWatched = { item: MediaItem ->
            item.path !in historySet && item.path !in progressPaths
        }

        // 封面：最近头部 + 全库随机抽样，避免永远只推 modified 最前的那几张
        val withCover = items.filter { it.coverLocalPath() != null && notWatched(it) }
        mixRecentAndRandom(withCover, sortedRecent.filter { it.coverLocalPath() != null && notWatched(it) }, limit, rnd)
            .forEachIndexed { i, item ->
                offer(item, RECOMMEND_REASON_COVER, 95 - i.coerceAtMost(50))
            }

        val unwatched = items.filter(notWatched)
        mixRecentAndRandom(unwatched, sortedRecent.filter(notWatched), limit, rnd)
            .forEachIndexed { i, item ->
                offer(item, RECOMMEND_REASON_UNWATCHED, 75 - i.coerceAtMost(50))
            }

        mixRecentAndRandom(items, sortedRecent, limit, rnd)
            .forEachIndexed { i, item ->
                offer(item, RECOMMEND_REASON_RECENT, 45 - i.coerceAtMost(50))
            }

        // 无观看锚点时仍保证全库有机会：补一轮纯随机低分候选
        if (historyItems.isEmpty() && items.size > limit) {
            items.shuffled(rnd).take(limit * 4).forEachIndexed { i, item ->
                offer(item, RECOMMEND_REASON_UNWATCHED, 55 - i.coerceAtMost(40))
            }
        }

        val ranked = candidates.values.sortedWith(
            compareByDescending<RecommendationCandidate> { it.score }
                .thenBy { stableJitter(seed xor 0x5bd1e995L, it.item.path) }
                .thenBy { it.item.displayTitle().lowercase() },
        )
        // 优先用未出现在冷却环的条目；不足再回填已推过的
        val fresh = ranked.filter { it.item.path !in cooldown }
        val cooled = ranked.filter { it.item.path in cooldown }
        val picked = (fresh + cooled).take(limit)
        val counts = picked.groupingBy { it.reason }.eachCount()
        val summary = recommendationReasonCounts(counts)
            .joinToString(" · ") { "${it.label} ${it.count}" }
        return RecommendationResult(
            items = picked.map { it.item },
            summary = if (summary.isBlank()) "" else "规则推荐 · $summary",
            reasons = picked.associate { it.item.path to it.reason },
        )
    }

    fun recommendationReasonCounts(counts: Map<String, Int>): List<RecommendationReasonCount> =
        RECOMMEND_REASON_ORDER.mapNotNull { reason ->
            val count = counts[reason] ?: return@mapNotNull null
            if (count <= 0) return@mapNotNull null
            RecommendationReasonCount(reason, recommendationReasonLabel(reason), count)
        }

    fun recommendationReasonLabel(reason: String): String =
        when (reason) {
            RECOMMEND_REASON_COLLECTION -> "同合集"
            RECOMMEND_REASON_TYPE -> "同类型"
            RECOMMEND_REASON_COVER -> "最近补全封面"
            RECOMMEND_REASON_UNWATCHED -> "未看"
            RECOMMEND_REASON_RECENT -> "最近入库"
            else -> reason
        }

    const val RECOMMEND_REASON_COLLECTION = "collection"
    const val RECOMMEND_REASON_TYPE = "type"
    const val RECOMMEND_REASON_COVER = "cover"
    const val RECOMMEND_REASON_UNWATCHED = "unwatched"
    const val RECOMMEND_REASON_RECENT = "recent"

    private val RECOMMEND_REASON_ORDER = listOf(
        RECOMMEND_REASON_COLLECTION,
        RECOMMEND_REASON_TYPE,
        RECOMMEND_REASON_COVER,
        RECOMMEND_REASON_UNWATCHED,
        RECOMMEND_REASON_RECENT,
    )

    private data class RecommendationCandidate(
        val item: MediaItem,
        val reason: String,
        val score: Int,
    )

    /** 与最高规则分同量级，保证「未推过」优先于「刚推过但高分」 */
    private const val COOLDOWN_PENALTY = 280

    /**
     * 最近入库头部 + 全池随机抽样混合，扩大候选面。
     * [recentOrdered] 已按 modified 降序；[pool] 为规则过滤后的全集。
     */
    private fun mixRecentAndRandom(
        pool: List<MediaItem>,
        recentOrdered: List<MediaItem>,
        limit: Int,
        rnd: Random,
    ): List<MediaItem> {
        if (pool.isEmpty()) return emptyList()
        val recentTake = (limit * 3).coerceAtLeast(1)
        val randomTake = (limit * 8).coerceAtLeast(1)
        val recent = recentOrdered.take(recentTake)
        val random = pool.shuffled(rnd).take(randomTake)
        return (recent + random).distinctBy { it.path }
    }

    /** 抖动加宽到 0..40，同档规则内换一批更易换序 */
    private fun stableJitter(seed: Long, key: String): Int =
        abs((seed xor key.hashCode().toLong()).toInt() % 41)

    fun historyItems(all: List<MediaItem>, paths: List<String>): List<MediaItem> {
        val map = all.associateBy { it.path }
        return paths.mapNotNull { map[it] }
    }

    fun watchQueueItems(all: List<MediaItem>, entries: List<WatchQueueStore.Entry>): List<MediaItem> {
        val map = all.associateBy { it.path }
        return entries.mapNotNull { map[it.path] }
    }

    fun search(items: List<MediaItem>, query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return items
        return items.filter { item ->
            val blob = item.searchBlob()
            if (blob.contains(q)) return@filter true
            val initials = SearchPinyin.compactInitials(item.displayTitle())
            if (initials.isNotBlank() && initials.contains(q)) return@filter true
            val cn = item.raw.optString("title_cn", "").trim()
            if (cn.isNotBlank()) {
                val cnInit = SearchPinyin.compactInitials(cn)
                if (cnInit.isNotBlank() && cnInit.contains(q)) return@filter true
            }
            false
        }
    }

    /**
     * 分批扫描；每命中 [batch] 条调用一次 onBatch（含全部已命中），扫描结束再调一次。
     * 调用方负责在 onBatch 内检查 isActive 决定是否继续，重型项目放后台线程。
     */
    inline fun searchStreaming(
        items: List<MediaItem>,
        query: String,
        batch: Int = 24,
        isCancelled: () -> Boolean = { false },
        onBatch: (List<MediaItem>, /*finished*/ Boolean) -> Unit,
    ) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            onBatch(emptyList(), true)
            return
        }
        val acc = ArrayList<MediaItem>(64)
        var sinceLast = 0
        for (item in items) {
            if (isCancelled()) return
            val blob = item.searchBlob()
            var hit = blob.contains(q)
            if (!hit) {
                val initials = SearchPinyin.compactInitials(item.displayTitle())
                if (initials.isNotBlank() && initials.contains(q)) hit = true
            }
            if (!hit) {
                val cn = item.raw.optString("title_cn", "").trim()
                if (cn.isNotBlank()) {
                    val cnInit = SearchPinyin.compactInitials(cn)
                    if (cnInit.isNotBlank() && cnInit.contains(q)) hit = true
                }
            }
            if (hit) {
                acc.add(item)
                sinceLast++
                if (sinceLast >= batch) {
                    onBatch(ArrayList(acc), false)
                    sinceLast = 0
                }
            }
        }
        onBatch(ArrayList(acc), true)
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
