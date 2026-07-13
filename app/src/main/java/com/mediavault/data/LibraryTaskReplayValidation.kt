package com.mediavault.data

data class LibraryTaskReplayValidation(
    val missingLocalRootUris: List<String>,
    val missingRemoteIds: List<String>,
    val hasConfiguredSource: Boolean,
    val canExecute: Boolean,
) {
    companion object {
        fun validate(
            scope: LibraryTaskReplayScope,
            configuredLocalRootUris: Collection<String>,
            configuredRemoteIds: Collection<String>,
        ): LibraryTaskReplayValidation {
            val localRootUris = configuredLocalRootUris.filter { it.isNotBlank() }.toSet()
            val remoteIds = configuredRemoteIds.filter { it.isNotBlank() }.toSet()
            val missingLocalRootUris = scope.localRootUris
                .filterNot { it in localRootUris }
                .distinct()
            val missingRemoteIds = scope.remoteIds
                .filterNot { it in remoteIds }
                .distinct()
            val hasConfiguredSource = localRootUris.isNotEmpty() || remoteIds.isNotEmpty()

            return LibraryTaskReplayValidation(
                missingLocalRootUris = missingLocalRootUris,
                missingRemoteIds = missingRemoteIds,
                hasConfiguredSource = hasConfiguredSource,
                canExecute = scope.isReplayable() && hasConfiguredSource &&
                    missingLocalRootUris.isEmpty() &&
                    missingRemoteIds.isEmpty(),
            )
        }
    }
}
