package com.mediavault.data

import com.mediavault.remote.RemotePath

data class LibrarySourceContext(
    val type: Type,
    val key: String,
) {
    enum class Type {
        LOCAL_ROOT,
        REMOTE_SOURCE,
    }

    companion object {
        fun fromPath(path: String, localRootUris: Collection<String>): LibrarySourceContext? {
            val remoteId = RemotePath.parse(path)?.configId?.takeIf { it.isNotBlank() }
            if (remoteId != null) {
                return LibrarySourceContext(Type.REMOTE_SOURCE, remoteId)
            }
            val localRoot = localRootUris
                .filter { it.isNotBlank() }
                .sortedByDescending { it.length }
                .firstOrNull { path.startsWith(it) }
                ?: return null
            return LibrarySourceContext(Type.LOCAL_ROOT, localRoot)
        }
    }
}
