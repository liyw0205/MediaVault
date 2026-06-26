package com.mediavault.remote

import android.net.Uri
import com.mediavault.data.MediaItem
import com.mediavault.data.MediaStore

object RemotePlayUri {
    /** ExoPlayer 用自定义 scheme，由 RemoteDataSource 解析 */
    fun forItem(store: MediaStore, item: MediaItem): Uri? {
        val parsed = RemotePath.parse(item.path) ?: return null
        val cfg = store.readRemotesList().find { it.id == parsed.configId } ?: return null
        return Uri.parse("mediavault-remote://${cfg.id}/${Uri.encode(parsed.relativePath)}")
    }
}