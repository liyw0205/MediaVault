package com.mediavault.data

import java.util.Collections

/** 单次刮削任务内缓存 scrape-record，避免每条路径整文件 readLines。 */
class ScrapeSession(store: MediaStore) {
    private val paths: MutableSet<String> = Collections.synchronizedSet(
        store.loadScrapeRecordPaths().toMutableSet(),
    )

    fun has(path: String): Boolean = paths.contains(path.trim())

    fun record(store: MediaStore, path: String) {
        val p = path.trim()
        synchronized(paths) {
            if (!paths.add(p)) return
        }
        store.appendScrapeRecordPath(p)
    }
}