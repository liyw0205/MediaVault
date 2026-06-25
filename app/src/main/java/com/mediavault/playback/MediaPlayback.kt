package com.mediavault.playback

import android.net.Uri
import com.mediavault.data.MediaItem

object MediaPlayback {
    fun resolvePlayUri(item: MediaItem): Uri? {
        val path = item.path.trim()
        if (path.startsWith("content://") && !path.startsWith("content://local/")) {
            return Uri.parse(path)
        }
        if (path.startsWith("file://")) return Uri.parse(path)
        if (path.startsWith("/")) return Uri.parse("file://$path")
        return null
    }
}