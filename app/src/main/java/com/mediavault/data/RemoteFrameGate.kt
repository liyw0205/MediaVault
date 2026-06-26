package com.mediavault.data

import java.util.concurrent.Semaphore

/** 限制远程视频抽帧并发，避免多线程同时 MediaMetadataRetriever。 */
class RemoteFrameGate(permits: Int) {
    private val sem = Semaphore(permits.coerceIn(ScrapeConfig.MIN_REMOTE_FRAME, ScrapeConfig.MAX_REMOTE_FRAME))

    fun <T> withPermit(block: () -> T): T {
        sem.acquire()
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}