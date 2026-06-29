package com.mediavault.ui

import android.content.Context
import com.mediavault.data.MediaItem
import org.json.JSONArray

/**
 * 推荐列表持久化：切换底栏、库增量更新都不重随机。
 * 仅工具栏「重读」或主页「刷新推荐」会清空并重建。
 */
object HomeRecommendState {
    private const val PREFS = "home_recommend"
    private const val KEY_PATHS = "paths"
    private const val KEY_SEED = "seed"
    private const val KEY_READY = "ready"
    private const val KEY_AUTO_SEEDED = "auto_seeded"

    private var paths: List<String> = emptyList()
    private var seed: Long = 0L
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
        val arr = p.getString(KEY_PATHS, null)?.let { JSONArray(it) }
        paths = if (arr != null) {
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } else emptyList()
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
    fun resolveItems(ctx: Context, filtered: List<MediaItem>): List<MediaItem> {
        ensureLoaded(ctx)
        if (!hasPersistedList()) return emptyList()
        val map = filtered.associateBy { it.path }
        return paths.mapNotNull { map[it] }.take(RECOMMEND_COUNT)
    }

    /**
     * 从当前库随机 20 条并写入磁盘（仅由「重读」「刷新推荐」调用）。
     */
    fun rebuildAndPersist(ctx: Context, filtered: List<MediaItem>): List<MediaItem> {
        seed = System.currentTimeMillis()
        val picked = LibraryUi.recommend(filtered, seed).take(RECOMMEND_COUNT)
        paths = picked.map { it.path }
        ready = true
        persist(ctx)
        return picked
    }

    fun clearPersist(ctx: Context) {
        ready = false
        seed = 0L
        paths = emptyList()
        loadedFromDisk = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PATHS)
            .remove(KEY_SEED)
            .putBoolean(KEY_READY, false)
            .apply()
    }

    /** 手动刷新推荐：清空列表，保留 auto_seeded，避免立刻再自动种子 */

    fun clearForManualRefresh(ctx: Context) = clearPersist(ctx)

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATHS, arr.toString())
            .putLong(KEY_SEED, seed)
            .putBoolean(KEY_READY, ready)
            .apply()
    }

    const val RECOMMEND_COUNT = 20
}