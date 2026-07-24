package com.mediavault.ui

import android.content.Context
import com.mediavault.data.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推荐列表持久化：切换底栏、库增量更新都不重抽。
 * 仅工具栏「重读」或主页「刷新推荐」会清空当前列表并重建。
 * 另持久化最近多批已推 path，换一批时优先避开，减少重复。
 */
object HomeRecommendState {
    private const val PREFS = "home_recommend"
    private const val KEY_PATHS = "paths"
    private const val KEY_REASONS = "reasons"
    private const val KEY_SEED = "seed"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_READY = "ready"
    private const val KEY_AUTO_SEEDED = "auto_seeded"
    private const val KEY_SHOWN = "shown_paths"

    /** 约 5 批 × 20 条，换一批时优先避开 */
    private const val SHOWN_HISTORY_LIMIT = 100

    private var paths: List<String> = emptyList()
    private var reasons: Map<String, String> = emptyMap()
    private var shownPaths: List<String> = emptyList()
    private var seed: Long = 0L
    private var summary: String = ""
    private var ready: Boolean = false
    private var autoSeeded: Boolean = false
    private var loadedFromDisk: Boolean = false

    fun ensureLoaded(ctx: Context) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ready = p.getBoolean(KEY_READY, false)
        autoSeeded = p.getBoolean(KEY_AUTO_SEEDED, false)
        seed = p.getLong(KEY_SEED, 0L)
        summary = p.getString(KEY_SUMMARY, "").orEmpty()
        paths = readPathList(p.getString(KEY_PATHS, null))
        shownPaths = readPathList(p.getString(KEY_SHOWN, null))
        val reasonObj = p.getString(KEY_REASONS, null)?.let { JSONObject(it) }
        reasons = if (reasonObj != null) {
            reasonObj.keys().asSequence()
                .mapNotNull { path ->
                    reasonObj.optString(path).takeIf { it.isNotBlank() }?.let { path to it }
                }
                .toMap()
        } else emptyMap()
    }

    fun hasPersistedList(): Boolean = ready && paths.isNotEmpty()

    /** 从未持久化且未做过「空列表自动刷新」时可自动随机一次 */
    fun shouldAutoSeedOnce(ctx: Context): Boolean {
        ensureLoaded(ctx)
        return !hasPersistedList() && !autoSeeded
    }

    fun markAutoSeeded(ctx: Context) {
        autoSeeded = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SEEDED, true)
            .apply()
    }

    /** 工具栏「重读」：允许下次进入推荐时再自动种子一次（若仍无列表） */
    fun resetAutoSeedFlag(ctx: Context) {
        autoSeeded = false
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SEEDED, false)
            .apply()
    }

    /** 按持久化 path 顺序映射当前库；条目不删则顺序不变 */
    fun resolveItems(ctx: Context, filtered: List<MediaItem>, reason: String? = null): List<MediaItem> {
        ensureLoaded(ctx)
        if (!hasPersistedList()) return emptyList()
        val map = filtered.associateBy { it.path }
        return paths.asSequence()
            .filter { reason.isNullOrBlank() || reasons[it] == reason }
            .mapNotNull { map[it] }
            .take(RECOMMEND_COUNT)
            .toList()
    }

    fun reasonCounts(ctx: Context, filtered: List<MediaItem>): List<LibraryUi.RecommendationReasonCount> {
        ensureLoaded(ctx)
        if (!hasPersistedList() || reasons.isEmpty()) return emptyList()
        val available = filtered.map { it.path }.toHashSet()
        val counts = paths.asSequence()
            .filter { it in available }
            .mapNotNull { reasons[it] }
            .groupingBy { it }
            .eachCount()
        return LibraryUi.recommendationReasonCounts(counts)
    }

    /**
     * 从当前库按可解释规则挑选 20 条并写入磁盘（仅由「重读」「刷新推荐」调用）。
     * 会参考 recently shown 冷却环，并在写入后把本批 path 并入冷却历史。
     */
    fun rebuildAndPersist(
        ctx: Context,
        filtered: List<MediaItem>,
        historyPaths: List<String>,
        progressPaths: Set<String>,
    ): List<MediaItem> {
        ensureLoaded(ctx)
        seed = System.currentTimeMillis()
        val result = LibraryUi.explainableRecommendations(
            items = filtered,
            historyPaths = historyPaths,
            progressPaths = progressPaths,
            seed = seed,
            limit = RECOMMEND_COUNT,
            recentlyShownPaths = shownPaths,
        )
        paths = result.items.map { it.path }
        reasons = result.reasons
        summary = result.summary
        ready = true
        shownPaths = mergeShownRing(shownPaths, paths)
        persist(ctx)
        return result.items
    }

    fun summary(ctx: Context): String {
        ensureLoaded(ctx)
        return summary
    }

    /**
     * 清空当前推荐列表（保留已推冷却环，换一批才能继续去重）。
     * 工具栏「重读」与手动刷新都走这里。
     */
    fun clearPersist(ctx: Context) {
        ready = false
        seed = 0L
        summary = ""
        paths = emptyList()
        reasons = emptyMap()
        loadedFromDisk = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PATHS)
            .remove(KEY_REASONS)
            .remove(KEY_SEED)
            .remove(KEY_SUMMARY)
            .putBoolean(KEY_READY, false)
            .apply()
    }

    /** 手动刷新推荐：清空当前列表，保留 auto_seeded 与 shown 冷却 */
    fun clearForManualRefresh(ctx: Context) = clearPersist(ctx)

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        val shownArr = JSONArray()
        shownPaths.forEach { shownArr.put(it) }
        val reasonObj = JSONObject()
        reasons.forEach { (path, reason) -> reasonObj.put(path, reason) }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATHS, arr.toString())
            .putString(KEY_REASONS, reasonObj.toString())
            .putString(KEY_SHOWN, shownArr.toString())
            .putLong(KEY_SEED, seed)
            .putString(KEY_SUMMARY, summary)
            .putBoolean(KEY_READY, ready)
            .apply()
    }

    private fun readPathList(raw: String?): List<String> {
        val arr = raw?.let { JSONArray(it) } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }

    /** 旧批在前、新批在后；同 path 移到末尾；超限从头部丢弃（先冷却完的先释放）。 */
    private fun mergeShownRing(previous: List<String>, batch: List<String>): List<String> {
        val ring = previous.toMutableList()
        for (path in batch) {
            if (path.isBlank()) continue
            ring.remove(path)
            ring.add(path)
        }
        return if (ring.size <= SHOWN_HISTORY_LIMIT) {
            ring
        } else {
            ring.subList(ring.size - SHOWN_HISTORY_LIMIT, ring.size).toList()
        }
    }

    const val RECOMMEND_COUNT = 20
}
