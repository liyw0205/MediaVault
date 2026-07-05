package com.mediavault.remote

import android.content.Context
import androidx.media3.common.C
import com.mediavault.data.ScrapeConfig
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程播放缓存：顺序 prefix + seek 区间 range 文件；拖动后二次命中读本地。
 */
object RemoteStreamCache {
    private const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024 * 1024
    private const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val lock = Any()

    private fun maxPerFile(context: Context): Long =
        ScrapeConfig.readSettings(context).remoteCacheMaxBytesPerFile

    private fun maxTotal(context: Context): Long =
        ScrapeConfig.readSettings(context).remoteCacheMaxTotalBytes

    fun configuredMaxTotalBytes(context: Context): Long = maxTotal(context)

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

    private val cacheKeyNameRegex = Regex("^[0-9a-f]{64}$")

    private fun rangeNamePrefix(key: String): String = "range_${key}_"

    private fun rangeFile(context: Context, key: String, start: Long, endExclusive: Long): File =
        File(cacheDir(context), "${rangeNamePrefix(key)}${start}_${endExclusive}.dat")

    private data class RangeName(val key: String, val start: Long, val endExclusive: Long)

    private data class RangeEntry(val start: Long, val endExclusive: Long, val file: File)

    private fun parseRangeName(name: String, requireValidBounds: Boolean): RangeName? {
        if (!name.startsWith("range_") || !name.endsWith(".dat")) return null
        val body = name.removePrefix("range_").removeSuffix(".dat")
        val keyEnd = body.indexOf('_')
        if (keyEnd <= 0) return null
        val key = body.substring(0, keyEnd)
        if (!cacheKeyNameRegex.matches(key)) return null
        val bounds = body.substring(keyEnd + 1)
        val split = bounds.lastIndexOf('_')
        if (split <= 0) return null
        val start = bounds.substring(0, split).toLongOrNull() ?: return null
        val endExclusive = bounds.substring(split + 1).toLongOrNull() ?: return null
        if (requireValidBounds && (start < 0L || endExclusive <= start)) return null
        return RangeName(key, start, endExclusive)
    }

    private fun parseCompleteRangeName(name: String): RangeName? =
        parseRangeName(name, requireValidBounds = true)

    private fun listRangeEntries(context: Context, key: String): List<RangeEntry> {
        val dir = cacheDir(context)
        return dir.listFiles()?.mapNotNull { f ->
            if (!f.isFile) return@mapNotNull null
            val range = parseCompleteRangeName(f.name) ?: return@mapNotNull null
            if (range.key != key) return@mapNotNull null
            RangeEntry(range.start, range.endExclusive, f)
        }?.sortedBy { it.start } ?: emptyList()
    }

    /**
     * 若已有 range 文件覆盖 [fileOffset, fileOffset+length)，从本地读出并返回已写字节数。
     */
    fun readRangeHit(
        context: Context,
        key: String,
        fileOffset: Long,
        length: Long,
        out: OutputStream,
        alive: AtomicBoolean,
    ): Long {
        if (!alive.get() || length <= 0L) return 0L
        val want = if (length == Long.MAX_VALUE) Long.MAX_VALUE else length
        var cursor = fileOffset
        var need = want
        var totalWritten = 0L
        for (entry in listRangeEntries(context, key)) {
            if (need <= 0L || !alive.get()) break
            if (cursor < entry.start || cursor >= entry.endExclusive) continue
            val inFileOff = cursor - entry.start
            val avail = entry.endExclusive - cursor
            val toRead = if (need == Long.MAX_VALUE) avail else minOf(need, avail)
            if (toRead <= 0L) continue
            val expectedLen = entry.endExclusive - entry.start
            if (entry.file.length() < expectedLen) continue
            var written = 0L
            val readOk = try {
                RandomAccessFile(entry.file, "r").use { raf ->
                    raf.seek(inFileOff)
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
                true
            } catch (_: IOException) {
                false
            }
            if (written > 0) entry.file.setLastModified(System.currentTimeMillis())
            cursor += written
            need -= if (need == Long.MAX_VALUE) written else written
            totalWritten += written
            if (!readOk) break
            if (need <= 0L) break
        }
        return totalWritten
    }

    private fun cachedBytesForKey(context: Context, key: String): Long {
        var sum = prefixLength(context, key)
        listRangeEntries(context, key).forEach { sum += it.file.length() }
        return sum
    }

    private fun completeCacheBytes(context: Context): Long {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return 0L
        return dir.listFiles()?.sumOf { f ->
            if (isCompleteCacheFile(f)) f.length() else 0L
        } ?: 0L
    }

    private fun remainingTotalCacheBytes(context: Context): Long =
        (maxTotal(context) - completeCacheBytes(context)).coerceAtLeast(0L)

    data class ItemCacheSummary(
        val prefixBytes: Long,
        val rangeFiles: Int,
        val rangeBytes: Long,
    ) {
        val totalBytes: Long = prefixBytes + rangeBytes
    }

    fun cacheSummaryForItem(context: Context, cfg: RemoteConfig, relativePath: String): ItemCacheSummary =
        cacheSummaryForKey(context, cacheKey(cfg, relativePath))

    fun cacheSummaryForKey(context: Context, key: String): ItemCacheSummary {
        val ranges = listRangeEntries(context, key)
        return ItemCacheSummary(
            prefixBytes = prefixLength(context, key),
            rangeFiles = ranges.size,
            rangeBytes = ranges.sumOf { it.file.length() },
        )
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
        if (length == 0L) return 0L
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
        try {
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
        } catch (_: IOException) {
            return written
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
        if (length == 0L) return
        synchronized(lock) {
            if (!alive.get()) return
            cleanup(context)
            val prefix = prefixFile(context, key)
            val prefixLen = if (prefix.isFile) prefix.length() else 0L
            if (netOffset < prefixLen) {
                throw IOException("fetch offset $netOffset behind prefix $prefixLen")
            }
            val cap = maxPerFile(context)
            RemoteReadRetry.openWithRetry(client, relativePath, netOffset, length).use { input ->
                val buf = ByteArray(64 * 1024)
                val append = netOffset == prefixLen && prefixLen < cap
                var cacheRemaining = if (append) {
                    minOf((cap - prefixLen).coerceAtLeast(0L), remainingTotalCacheBytes(context))
                } else {
                    0L
                }
                var fos = if (append && cacheRemaining > 0L) {
                    runCatching { FileOutputStream(prefix, true) }.getOrElse {
                        cacheRemaining = 0L
                        null
                    }
                } else {
                    null
                }
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
                        val writer = fos
                        if (writer != null && cacheRemaining > 0L) {
                            val toCache = minOf(n.toLong(), cacheRemaining).toInt()
                            if (toCache > 0) {
                                val wrote = runCatching { writer.write(buf, 0, toCache) }.isSuccess
                                if (wrote) {
                                    cacheRemaining -= toCache.toLong()
                                } else {
                                    cacheRemaining = 0L
                                }
                            }
                            if (cacheRemaining <= 0L) {
                                runCatching { writer.flush() }
                                runCatching { writer.close() }
                                fos = null
                            }
                        }
                        if (remaining != Long.MAX_VALUE) remaining -= n.toLong()
                    }
                } finally {
                    fos?.let { writer ->
                        runCatching { writer.flush() }
                        runCatching { writer.close() }
                    }
                }
            }
            if (alive.get()) prefix.setLastModified(System.currentTimeMillis())
        }
    }

    private fun shouldCacheRange(length: Long): Boolean =
        length > 0 && length != C.LENGTH_UNSET.toLong() && length != Long.MAX_VALUE

    private fun rangeEndExclusive(start: Long, length: Long): Long? {
        if (!shouldCacheRange(length) || start < 0L || start > Long.MAX_VALUE - length) return null
        return start + length
    }

    /**
     * seek 到 prefix 未覆盖的字节：拉网写播放器；若本次请求长度已知则同时落盘 range 区间。
     */
    fun fetchDirect(
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
        if (length == 0L) return
        cleanup(context)

        var readOffset = netOffset
        var readLength = length
        val exactEndExclusive = rangeEndExclusive(netOffset, length)
        val exactRange = exactEndExclusive?.let { rangeFile(context, key, netOffset, it) }

        if (exactRange != null && exactRange.isFile && exactRange.length() >= length) {
            val cached = readRangeHit(context, key, netOffset, length, out, alive)
            if (cached >= length || !alive.get()) return
            runCatching { exactRange.delete() }
            if (cached > 0L) {
                readOffset += cached
                readLength -= cached
            }
        }

        val endExclusive = rangeEndExclusive(readOffset, readLength)
        val rangeTarget = endExclusive?.let {
            val dest = rangeFile(context, key, readOffset, it)
            File(dest.parent, "${dest.name}.part") to dest
        }

        val partRange = rangeTarget?.first
        if (partRange != null && partRange.isFile) partRange.delete()

        val cap = maxPerFile(context)
        val remainingCacheBytes = minOf(
            (cap - cachedBytesForKey(context, key)).coerceAtLeast(0L),
            remainingTotalCacheBytes(context),
        )
        val allowRangeWrite = readLength <= remainingCacheBytes

        RemoteReadRetry.openWithRetry(client, relativePath, readOffset, readLength).use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = if (readLength > 0 && readLength != C.LENGTH_UNSET.toLong()) {
                readLength
            } else {
                Long.MAX_VALUE
            }
            var rangeWritten = 0L
            var rangeCacheFailed = false
            var fos = if (allowRangeWrite && partRange != null) {
                runCatching { FileOutputStream(partRange, false) }.getOrElse {
                    rangeCacheFailed = true
                    null
                }
            } else {
                null
            }
            try {
                while (remaining > 0 && alive.get()) {
                    val toRead = if (remaining == Long.MAX_VALUE) {
                        buf.size
                    } else {
                        minOf(buf.size.toLong(), remaining).toInt()
                    }
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    if (!writeOut(out, buf, 0, n, alive)) break
                    val writer = fos
                    if (writer != null) {
                        val wrote = runCatching { writer.write(buf, 0, n) }.isSuccess
                        if (wrote) {
                            rangeWritten += n.toLong()
                        } else {
                            rangeCacheFailed = true
                            runCatching { writer.close() }
                            fos = null
                            partRange?.delete()
                        }
                    }
                    if (remaining != Long.MAX_VALUE) remaining -= n.toLong()
                }
            } finally {
                fos?.let { writer ->
                    runCatching { writer.flush() }
                    runCatching { writer.close() }
                }
            }
            val target = rangeTarget
            if (!rangeCacheFailed && allowRangeWrite && target != null && rangeWritten >= readLength) {
                synchronized(lock) {
                    cleanup(context)
                    val part = target.first
                    val dest = target.second
                    val canCommit = readLength <= minOf(
                        (cap - cachedBytesForKey(context, key)).coerceAtLeast(0L),
                        remainingTotalCacheBytes(context),
                    )
                    if (canCommit && part.length() >= readLength && part.renameTo(dest)) {
                        dest.setLastModified(System.currentTimeMillis())
                    } else {
                        part.delete()
                    }
                }
            } else {
                partRange?.delete()
            }
        }
        val f = prefixFile(context, key)
        if (f.isFile && alive.get()) f.setLastModified(System.currentTimeMillis())
    }

    private fun cleanup(context: Context) {
        val dir = cacheDir(context)
        val now = System.currentTimeMillis()
        val totalCap = maxTotal(context)
        val files = dir.listFiles()?.filter { isCacheFile(it) }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        fun deleteTracked(f: File): Boolean {
            val len = f.length()
            if (!f.delete()) return false
            total = (total - len).coerceAtLeast(0L)
            files.remove(f)
            return true
        }
        for (f in files.toList()) {
            if (isTempCacheFile(f)) {
                if (now - f.lastModified() > 24 * 60 * 60 * 1000L) {
                    deleteTracked(f)
                }
                continue
            }
            if (now - f.lastModified() > MAX_AGE_MS) {
                deleteTracked(f)
            }
        }
        for (f in files.sortedBy { it.lastModified() }) {
            if (!isCompleteCacheFile(f)) continue
            if (total <= totalCap) break
            deleteTracked(f)
        }
    }

    fun clearAll(context: Context): Int {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return 0
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (isCacheFile(f) && f.delete()) n++
        }
        return n
    }

    private fun isCompletePrefixCacheName(name: String): Boolean {
        if (!name.startsWith("prefix_") || !name.endsWith(".dat")) return false
        val key = name.removePrefix("prefix_").removeSuffix(".dat")
        return cacheKeyNameRegex.matches(key)
    }

    private fun isCompleteRangeCacheName(name: String): Boolean =
        parseCompleteRangeName(name) != null

    private fun isRangeCacheArtifactName(name: String): Boolean =
        parseRangeName(name, requireValidBounds = false) != null

    private fun isCompleteCacheName(name: String): Boolean =
        isCompletePrefixCacheName(name) || isCompleteRangeCacheName(name)

    private fun isCompleteCacheFile(f: File): Boolean =
        f.isFile && isCompleteCacheName(f.name)

    private fun isTempCacheFile(f: File): Boolean {
        if (!f.isFile || !f.name.endsWith(".part")) return false
        val baseName = f.name.removeSuffix(".part")
        return isCompleteCacheName(baseName) || isRangeCacheArtifactName(baseName)
    }

    private fun isCacheFile(f: File): Boolean =
        isCompleteCacheFile(f) || isTempCacheFile(f)

    fun cacheStats(context: Context): Pair<Int, Long> {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return 0 to 0L
        var bytes = 0L
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (!isCompleteCacheFile(f)) return@forEach
            n++
            bytes += f.length()
        }
        return n to bytes
    }

    data class CacheBreakdown(
        val prefixFiles: Int,
        val prefixBytes: Long,
        val rangeFiles: Int,
        val rangeBytes: Long,
        val maxPerFileBytes: Long,
        val maxTotalBytes: Long,
    )

    fun cacheBreakdown(context: Context): CacheBreakdown {
        val dir = cacheDir(context)
        var prefixCount = 0
        var prefixBytes = 0L
        var rangeCount = 0
        var rangeBytes = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                if (!isCompleteCacheFile(f)) return@forEach
                when {
                    f.name.startsWith("prefix_") -> {
                        prefixCount++
                        prefixBytes += f.length()
                    }
                    f.name.startsWith("range_") -> {
                        rangeCount++
                        rangeBytes += f.length()
                    }
                }
            }
        }
        return CacheBreakdown(
            prefixFiles = prefixCount,
            prefixBytes = prefixBytes,
            rangeFiles = rangeCount,
            rangeBytes = rangeBytes,
            maxPerFileBytes = maxPerFile(context),
            maxTotalBytes = maxTotal(context),
        )
    }
}
