package com.mediavault.data

import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemotePath

data class RemoteMappingPreview(
    val affectedCount: Int,
    val samplePaths: List<String>,
) {
    val needsConfirmation: Boolean get() = affectedCount > 0

    companion object {
        fun create(previous: RemoteConfig, next: RemoteConfig, items: Collection<MediaItem>): RemoteMappingPreview {
            val changed = previous.type != next.type || previous.host != next.host ||
                previous.port != next.port || previous.basePath != next.basePath
            if (!changed) return RemoteMappingPreview(0, emptyList())
            val affected = items.filter { RemotePath.parse(it.path)?.configId == previous.id }
            return RemoteMappingPreview(affected.size, affected.take(3).map { it.displayTitle() })
        }
    }
}
