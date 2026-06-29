package com.mediavault.scrape

/**
 * 刮削 P2：降低前台通知刷新、scrape-job.json 写入与 UI 状态发射频率。
 */
object ScrapeProgressThrottle {
    const val NOTIF_EVERY_ITEMS = 15
    const val NOTIF_MIN_INTERVAL_MS = 4_000L
    const val JOB_PERSIST_EVERY_ITEMS = 12
    const val UI_TITLE_EVERY_ITEMS = 3
    const val UI_EMIT_MIN_INTERVAL_MS = 200L

    fun shouldRefreshNotification(itemIndex: Int, lastNotifAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (itemIndex <= 1) return true
        if (itemIndex % NOTIF_EVERY_ITEMS == 0) return true
        return nowMs - lastNotifAtMs >= NOTIF_MIN_INTERVAL_MS && itemIndex % 5 == 0
    }

    fun shouldPersistJob(batchCount: Int, lastPersistedBatch: Int): Boolean =
        batchCount <= 1 || batchCount - lastPersistedBatch >= JOB_PERSIST_EVERY_ITEMS

    fun shouldEmitUi(
        batchCount: Int,
        lastEmitAtMs: Long,
        force: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (force) return true
        if (batchCount <= 1) return true
        return nowMs - lastEmitAtMs >= UI_EMIT_MIN_INTERVAL_MS
    }
}