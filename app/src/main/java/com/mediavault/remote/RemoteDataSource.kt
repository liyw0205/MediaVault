package com.mediavault.remote

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mediavault.data.LibraryDiagnosticsStore
import com.mediavault.data.MediaStore
import com.mediavault.data.RemoteCapability
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RemoteDataSource(
    private val context: Context,
    private val store: MediaStore,
) : DataSource {

    private var stream: java.io.InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val readerAlive = AtomicBoolean(true)
    private val readerError = AtomicReference<IOException?>(null)

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        closeQuietPipe()
        readerAlive.set(true)
        readerError.set(null)
        uri = dataSpec.uri
        val u = dataSpec.uri
        if (u.scheme != "mediavault-remote") {
            throw IOException(RemoteErrorMessages.userMessage(context, IOException("bad scheme")))
        }
        val configId = u.host ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("no id")))
        val encPath = u.encodedPath?.trimStart('/') ?: ""
        val rel = Uri.decode(encPath)
        val cfg = resolveConfig(configId)
            ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("not configured")))
        val client = RemoteClients.create(cfg)
        val cacheKey = RemoteStreamCache.cacheKey(cfg, rel)

        val position = dataSpec.position
        val requestLength = dataSpec.length
        val totalSize = try {
            client.fileSize(rel)
        } catch (t: Throwable) {
            recordPlaybackCapability(
                cfg = cfg,
                rel = rel,
                cacheKey = cacheKey,
                trigger = "playback_prepare",
                fileSize = C.LENGTH_UNSET.toLong(),
                canOpen = false,
                supportsRange = null,
                error = t,
            )
            throw toRemoteIOException(t)
        }
        recordPlaybackCapability(
            cfg = cfg,
            rel = rel,
            cacheKey = cacheKey,
            trigger = "playback_prepare",
            fileSize = totalSize,
            canOpen = null,
            supportsRange = null,
        )

        val pipeIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val alive = readerAlive
        val readStartedAt = System.currentTimeMillis()
        thread(name = "mv-remote-read", isDaemon = true) {
            try {
                fillFromCacheAndNetwork(
                    client, rel, cacheKey, position, requestLength, pipeOut, alive,
                )
                val elapsed = (System.currentTimeMillis() - readStartedAt).coerceAtLeast(0L)
                recordPlaybackCapability(
                    cfg = cfg,
                    rel = rel,
                    cacheKey = cacheKey,
                    trigger = "playback_read",
                    fileSize = totalSize,
                    canOpen = true,
                    supportsRange = if (position > 0L) true else null,
                    firstByteMs = if (position <= 0L) elapsed else -1L,
                    seekReadMs = if (position > 0L) elapsed else -1L,
                )
            } catch (t: Throwable) {
                if (alive.get()) {
                    val io = toRemoteIOException(t)
                    readerError.set(io)
                    recordPlaybackCapability(
                        cfg = cfg,
                        rel = rel,
                        cacheKey = cacheKey,
                        trigger = "playback_read",
                        fileSize = totalSize,
                        canOpen = false,
                        supportsRange = if (position > 0L) false else null,
                        error = io,
                    )
                }
            } finally {
                runCatching { pipeOut.close() }
            }
        }
        stream = pipeIn

        bytesRemaining = when {
            requestLength != C.LENGTH_UNSET.toLong() -> requestLength
            totalSize != C.LENGTH_UNSET.toLong() && totalSize > position ->
                totalSize - position
            else -> C.LENGTH_UNSET.toLong()
        }
        return bytesRemaining
    }

    private fun resolveConfig(configId: String): RemoteConfig? {
        if (configId == RemotePlayUri.PREVIEW_CONFIG_ID) {
            RemoteBrowsePreviewHolder.config?.let { return it }
        }
        return store.readRemotesList().find { it.id == configId }
    }

    private fun fillFromCacheAndNetwork(
        client: RemoteClient,
        rel: String,
        cacheKey: String,
        position: Long,
        requestLength: Long,
        out: java.io.OutputStream,
        alive: AtomicBoolean,
    ) {
        if (!alive.get()) return
        val prefixLen = RemoteStreamCache.prefixLength(context, cacheKey)
        var cursor = position
        var need = if (requestLength != C.LENGTH_UNSET.toLong()) requestLength else Long.MAX_VALUE

        if (cursor < prefixLen) {
            val fromCache = minOf(prefixLen - cursor, need)
            val read = RemoteStreamCache.readPrefix(context, cacheKey, cursor, fromCache, out, alive)
            cursor += read
            need -= read
            if (need <= 0L || !alive.get()) return
        }

        if (!alive.get()) return

        if (need > 0L) {
            val rangeNeed = if (need == Long.MAX_VALUE) Long.MAX_VALUE else need
            val fromRange = RemoteStreamCache.readRangeHit(
                context, cacheKey, cursor, rangeNeed, out, alive,
            )
            cursor += fromRange
            if (need != Long.MAX_VALUE) need -= fromRange
            if (need <= 0L || !alive.get()) return
        }

        if (!alive.get()) return

        val netLen = if (need == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else need
        val prefixLenNow = RemoteStreamCache.prefixLength(context, cacheKey)
        if (cursor == prefixLenNow) {
            RemoteStreamCache.fetchAndAppend(context, cacheKey, client, rel, cursor, netLen, out, alive)
        } else {
            RemoteStreamCache.fetchDirect(context, cacheKey, client, rel, cursor, netLen, out, alive)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = stream ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("not opened")))
        val n = s.read(buffer, offset, length)
        if (n == -1) {
            readerError.get()?.let { throw it }
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n.toLong()
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        readerAlive.set(false)
        closeQuietPipe()
        uri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    private fun closeQuietPipe() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun toRemoteIOException(t: Throwable): IOException =
        if (t is IOException && !t.message.isNullOrBlank()) {
            t
        } else {
            IOException(RemoteErrorMessages.userMessage(context, t), t)
        }

    private fun recordPlaybackCapability(
        cfg: RemoteConfig,
        rel: String,
        cacheKey: String,
        trigger: String,
        fileSize: Long,
        canOpen: Boolean?,
        supportsRange: Boolean?,
        firstByteMs: Long = -1L,
        seekReadMs: Long = -1L,
        error: Throwable? = null,
    ) {
        val cache = RemoteStreamCache.cacheSummaryForKey(context, cacheKey)
        LibraryDiagnosticsStore(context).recordRemoteCapability(
            RemoteCapability(
                key = RemoteCapability.keyFor(cfg.id, rel, trigger),
                sourceId = cfg.id,
                sourceType = cfg.type.ifBlank { "remote" },
                name = cfg.name,
                relativePath = rel,
                lastCheckedAt = nowText(),
                trigger = trigger,
                canList = null,
                canOpen = canOpen,
                supportsRange = supportsRange,
                fileSize = fileSize,
                firstByteMs = firstByteMs,
                seekReadMs = seekReadMs,
                cachePrefixBytes = cache.prefixBytes,
                cacheRangeFiles = cache.rangeFiles,
                cacheRangeBytes = cache.rangeBytes,
                cacheTotalBytes = cache.totalBytes,
                lastErrorKind = error?.let { it::class.java.simpleName }.orEmpty(),
                lastErrorMessage = error?.let { RemoteErrorMessages.userMessage(context, it) }.orEmpty(),
            ),
        )
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

class RemoteDataSourceFactory(
    private val context: Context,
    private val store: MediaStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RemoteDataSource(context, store)
}
