package com.mediavault.remote

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mediavault.data.MediaStore
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RemoteDataSource(
    private val context: Context,
    private val store: MediaStore,
) : DataSource {

    private var stream: java.io.InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val readerAlive = AtomicBoolean(true)

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        closeQuietPipe()
        readerAlive.set(true)
        uri = dataSpec.uri
        val u = dataSpec.uri
        if (u.scheme != "mediavault-remote") {
            throw IOException(RemoteErrorMessages.userMessage(context, IOException("bad scheme")))
        }
        val configId = u.host ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("no id")))
        val encPath = u.path?.trimStart('/') ?: ""
        val rel = Uri.decode(encPath)
        val cfg = resolveConfig(configId)
            ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("not configured")))
        val client = RemoteClients.create(cfg)
        val cacheKey = RemoteStreamCache.cacheKey(cfg, rel)

        val position = dataSpec.position
        val requestLength = dataSpec.length
        val totalSize = client.fileSize(rel)

        val pipeIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val alive = readerAlive
        thread(name = "mv-remote-read", isDaemon = true) {
            try {
                fillFromCacheAndNetwork(
                    client, rel, cacheKey, position, requestLength, pipeOut, alive,
                )
            } catch (e: IOException) {
                if (alive.get()) {
                    // 非主动 close 时的网络/协议错误，交给读端 EOF
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

    private fun resolveConfig(configId: String): RemoteConfig? =
        store.readRemotesList().find { it.id == configId }
            ?: if (configId == RemotePlayUri.PREVIEW_CONFIG_ID) RemoteBrowsePreviewHolder.config else null

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
        if (cursor < prefixLen) {
            throw IOException(RemoteErrorMessages.userMessage(context, IOException("缓存缺口")))
        }

        val netLen = if (need == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else need
        RemoteStreamCache.fetchAndAppend(context, cacheKey, client, rel, cursor, netLen, out, alive)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = stream ?: throw IOException(RemoteErrorMessages.userMessage(context, IOException("not opened")))
        val n = s.read(buffer, offset, length)
        if (n == -1) return C.RESULT_END_OF_INPUT
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
}

class RemoteDataSourceFactory(
    private val context: Context,
    private val store: MediaStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RemoteDataSource(context, store)
}