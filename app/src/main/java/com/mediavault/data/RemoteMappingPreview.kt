package com.mediavault.data

import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemotePath

sealed class RemoteMappingPreview {
    abstract val changes: List<ChangeField>
    abstract val affectedCount: Int
    abstract val samplePaths: List<String>
    abstract val needsConfirmation: Boolean
    abstract val policy: UpdatePolicy?

    data object Unchanged : RemoteMappingPreview() {
        override val changes: List<ChangeField> = emptyList()
        override val affectedCount: Int = 0
        override val samplePaths: List<String> = emptyList()
        override val needsConfirmation: Boolean = false
        override val policy: UpdatePolicy? = null
    }

    data class Affected(
        override val changes: List<ChangeField>,
        override val affectedCount: Int,
        override val samplePaths: List<String>,
        override val policy: UpdatePolicy = UpdatePolicy.RECHECK_WITHOUT_PATH_REWRITE,
    ) : RemoteMappingPreview() {
        override val needsConfirmation: Boolean = true
    }

    enum class ChangeField {
        PROTOCOL,
        HOST,
        PORT,
        BASE_PATH,
    }

    enum class UpdatePolicy {
        RECHECK_WITHOUT_PATH_REWRITE,
    }

    companion object {
        fun create(previous: RemoteConfig, next: RemoteConfig, items: Collection<MediaItem>): RemoteMappingPreview {
            val changes = buildList {
                if (previous.type != next.type) add(ChangeField.PROTOCOL)
                if (previous.host != next.host) add(ChangeField.HOST)
                if (previous.port != next.port) add(ChangeField.PORT)
                if (previous.basePath != next.basePath) add(ChangeField.BASE_PATH)
            }
            if (changes.isEmpty()) return Unchanged

            val affected = items.filter { RemotePath.parse(it.path)?.configId == previous.id }
            return Affected(
                changes = changes,
                affectedCount = affected.size,
                samplePaths = affected.take(SAMPLE_LIMIT).map { it.displayTitle() },
            )
        }

        private const val SAMPLE_LIMIT = 3
    }
}
