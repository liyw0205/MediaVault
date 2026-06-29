package com.mediavault.remote

import android.net.Uri
import com.mediavault.data.MediaStore

object RemotePlayUri {
    const val PREVIEW_CONFIG_ID = "preview"

    /** ExoPlayer 用自定义 scheme，由 RemoteDataSource 解析 */
    fun forItem(store: MediaStore, item: com.mediavault.data.MediaItem): Uri? {
        val parsed = RemotePath.parse(item.path) ?: return null
        val cfg = store.readRemotesList().find { it.id == parsed.configId } ?: return null
        return forRelative(cfg.id, parsed.relativePath)
    }

    fun forRelative(configId: String, relativePath: String): Uri {
        val rel = relativePath.replace('\\', '/').trimStart('/')
        return Uri.parse("mediavault-remote://$configId/${Uri.encode(rel)}")
    }

    fun libraryPath(configId: String, relativePath: String): String =
        RemotePath.encode(configId, relativePath)
}