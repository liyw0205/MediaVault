package com.mediavault.playback

import android.net.Uri
import com.mediavault.data.MediaItem
import com.mediavault.data.MediaStore
import com.mediavault.remote.RemotePath
import com.mediavault.remote.RemotePlayUri

object MediaPlayback {
    fun resolvePlayUri(store: MediaStore, item: MediaItem): Uri? {
        val path = item.path
        if (path.startsWith("content://")) return Uri.parse(path)
        if (RemotePath.isRemote(path)) return RemotePlayUri.forItem(store, item)
        return null
    }
}