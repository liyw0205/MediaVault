package com.mediavault.data

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** 刮削过程中内存合并，按条数批量写盘，减少整库 JSON 重写次数。 */
class ScrapeBatchAccumulator(
    private val repository: LibraryRepository,
    private val flushEvery: Int = 25,
) {
    private val pending = ConcurrentLinkedQueue<MediaItem>()
    private val sinceFlush = AtomicInteger(0)
    @Volatile
    private var flushedTotal: Int = repository.library.value.items.size

    fun offer(item: MediaItem) {
        pending.add(item)
    }

    /** 入队后若达到阈值则刷盘；返回当前库条数。 */
    fun afterOffer(): Int {
        if (sinceFlush.incrementAndGet() >= flushEvery) return flushAll()
        return flushedTotal
    }

    fun flushAll(): Int {
        val batch = mutableListOf<MediaItem>()
        while (true) {
            val x = pending.poll() ?: break
            batch.add(x)
        }
        if (batch.isEmpty()) return flushedTotal
        flushedTotal = repository.mergeContentBatchWithoutReload(batch).getOrThrow()
        sinceFlush.set(0)
        return flushedTotal
    }

    fun currentLibrarySize(): Int = flushedTotal
}