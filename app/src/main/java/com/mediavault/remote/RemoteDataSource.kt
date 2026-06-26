package com.mediavault.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mediavault.data.MediaStore
import java.io.IOException
import java.io.InputStream

class RemoteDataSource(
    private val store: MediaStore,
) : DataSource {

    private var stream: InputStream? = null
    private var opened = false
    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val u = dataSpec.uri
        if (u.scheme != "mediavault-remote") throw IOException("Unsupported URI: $u")
        val configId = u.host ?: throw IOException("Missing remote id")
        val encPath = u.path?.trimStart('/') ?: ""
        val rel = Uri.decode(encPath)
        val cfg = store.readRemotesList().find { it.id == configId }
            ?: if (configId == RemotePlayUri.PREVIEW_CONFIG_ID) {
                RemoteBrowsePreviewHolder.config
            } else {
                null
            }
            ?: throw IOException("Remote not configured: $configId")
        val client = RemoteClients.create(cfg)
        stream = client.openRead(rel, dataSpec.position).also { opened = true }
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = stream ?: throw IOException("Not opened")
        val n = s.read(buffer, offset, length)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n.toLong()
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        opened = false
        uri = null
    }
}

class RemoteDataSourceFactory(private val store: MediaStore) : DataSource.Factory {
    override fun createDataSource(): DataSource = RemoteDataSource(store)
}