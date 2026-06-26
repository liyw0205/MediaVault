package com.mediavault.remote

import android.content.Context
import androidx.media3.common.C
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程流边下边播前缀缓存（对齐 Neribox prefix 思路）：顺序下载写入本地，seek 时先读缓存再拉网。
 */
object RemoteStreamCache {
    private const val MAX_CACHE_BYTES = 512L * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val lock = Any()

    fun cacheDir(context: Context): File =
        File(context.filesDir, "mediavault/remote_stream_cache").also { it.mkdirs() }

    fun cacheKey(cfg: RemoteConfig, relativePath: String): String {
        val raw = "${cfg.type}|${cfg.host}|${cfg.port}|${cfg.basePath}|${cfg.user}|$relativePath"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun prefixFile(context: Context, key: String): File =
        File(cacheDir(context), "prefix_$key.dat")

    fun prefixLength(context: Context, key: String): Long {
        val f = prefixFile(context, key)
        return if (f.isFile) f.length() else 0L
    }

    private fun writeOut(out: OutputStream, buf: ByteArray, off: Int, len: Int, alive: AtomicBoolean): Boolean {
        if (!alive.get()) return false
        return try {
            out.write(buf, off, len)
            true
        } catch (_: IOException) {
            alive.set(false)
            false
        }
    }

    fun readPrefix(
        context: Context,
        key: String,
        fileOffset: Long,
        length: Long,
        out: OutputStream,
        alive: AtomicBoolean,
    ): Long {
        val f = prefixFile(context, key)
        if (!f.isFile) return 0L
        val avail = (f.length() - fileOffset).coerceAtLeast(0L)
        if (avail <= 0L) return 0L
        val toRead = if (length > 0 && length != Long.MAX_VALUE) {
            minOf(length, avail)
        } else {
            avail
        }
        var written = 0L
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(fileOffset)
            val buf = ByteArray(64 * 1024)
            var left = toRead
            while (left > 0 && alive.get()) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                if (!writeOut(out, buf, 0, n, alive)) break
                left -= n.toLong()
                written += n.toLong()
            }
        }
        if (written > 0) f.setLastModified(System.currentTimeMillis())
        return written
    }

    fun fetchAndAppend(
        context: Context,
        key: String,
        client: RemoteClient,
        relativePath: String,
        netOffset: Long,
        length: Long,
        out: OutputStream,
        alive: AtomicBoolean,
    ) {
        if (!alive.get()) return
        synchronized(lock) {
            if (!alive.get()) return
            cleanup(context)
            val prefix = prefixFile(context, key)
            val prefixLen = if (prefix.isFile) prefix.length() else 0L
            if (netOffset < prefixLen) {
                throw IOException("fetch offset $netOffset behind prefix $prefixLen")
            }
            client.openRead(relativePath, netOffset, length).use { input ->
                val buf = ByteArray(64 * 1024)
                val append = netOffset == prefixLen
                val fos = if (append) FileOutputStream(prefix, true) else null
                try {
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
                        if (!writeOut(out, buf, 0, n, alive)) break
                        if (append && fos != null) {
                            runCatching { fos.write(buf, 0, n) }
                        }
                        if (remaining != Long.MAX_VALUE) remaining -= n.toLong()
                    }
                } finally {
                    fos?.close()
                }
            }
            if (alive.get()) prefix.setLastModified(System.currentTimeMillis())
        }
    }

    private fun cleanup(context: Context) {
        val dir = cacheDir(context)
        val now = System.currentTimeMillis()
        var total = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        val files = dir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        for (f in files.toList()) {
            if (now - f.lastModified() > MAX_AGE_MS) {
                total -= f.length()
                f.delete()
            }
        }
        for (f in files.sortedBy { it.lastModified() }) {
            if (!f.isFile) continue
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** 删除全部远程点播前缀缓存文件，返回删除个数 */
    fun clearAll(context: Context): Int {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return 0
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) n++
        }
        return n
    }

    fun cacheStats(context: Context): Pair<Int, Long> {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return 0 to 0L
        var bytes = 0L
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            n++
            bytes += f.length()
        }
        return n to bytes
    }
}