package com.mediavault.remote

import androidx.media3.common.C
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * 远程 openRead 短时重试（断线、超时），避免一次失败即 EOF。
 */
object RemoteReadRetry {
    private const val MAX_ATTEMPTS = 3
    private const val INITIAL_DELAY_MS = 400L

    fun openWithRetry(
        client: RemoteClient,
        relativePath: String,
        offset: Long,
        length: Long,
    ): InputStream {
        var last: Throwable? = null
        var delay = INITIAL_DELAY_MS
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return client.openRead(relativePath, offset, length)
            } catch (e: IOException) {
                last = e
                if (!isRetriable(e) || attempt == MAX_ATTEMPTS - 1) throw e
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(3_000L)
            }
        }
        throw last as? IOException ?: IOException("remote read failed")
    }

    private fun isRetriable(e: IOException): Boolean {
        if (e is SocketTimeoutException) return true
        val msg = e.message?.lowercase().orEmpty()
        if (msg.contains("connection reset") || msg.contains("broken pipe")) return true
        if (msg.contains("unexpected end of stream") || msg.contains("timeout")) return true
        if (msg.startsWith("webdav get") && msg.any { it.isDigit() }) {
            val code = msg.filter { it.isDigit() }.take(3).toIntOrNull()
            if (code != null && code in 500..599) return true
        }
        return false
    }

    fun readLoopToOut(
        input: InputStream,
        out: java.io.OutputStream,
        length: Long,
        alive: java.util.concurrent.atomic.AtomicBoolean,
        onChunk: ((ByteArray, Int, Int) -> Unit)? = null,
    ) {
        val buf = ByteArray(64 * 1024)
        var remaining = if (length > 0 && length != C.LENGTH_UNSET.toLong()) {
            length
        } else {
            Long.MAX_VALUE
        }
        while (remaining > 0 && alive.get()) {
            val toRead = if (remaining == Long.MAX_VALUE) {
                buf.size
            } else {
                minOf(buf.size.toLong(), remaining).toInt()
            }
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            if (!alive.get()) break
            try {
                out.write(buf, 0, n)
            } catch (_: IOException) {
                alive.set(false)
                break
            }
            onChunk?.invoke(buf, 0, n)
            if (remaining != Long.MAX_VALUE) remaining -= n.toLong()
        }
    }
}