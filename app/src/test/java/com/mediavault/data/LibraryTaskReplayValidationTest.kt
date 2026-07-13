package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTaskReplayValidationTest {
    @Test
    fun fullLibrary_requiresAtLeastOneConfiguredSource() {
        val scope = fullLibraryScope()

        val empty = LibraryTaskReplayValidation.validate(scope, listOf(""), listOf("   "))
        val local = LibraryTaskReplayValidation.validate(
            scope,
            listOf("content://tree/primary%3AMovies"),
            emptyList(),
        )
        val remote = LibraryTaskReplayValidation.validate(scope, emptyList(), listOf("remote-a"))

        assertFalse(empty.canExecute)
        assertFalse(empty.hasConfiguredSource)
        assertEquals(emptyList<String>(), empty.missingLocalRootUris)
        assertEquals(emptyList<String>(), empty.missingRemoteIds)
        assertTrue(local.hasConfiguredSource)
        assertTrue(remote.hasConfiguredSource)
        assertTrue(local.canExecute)
        assertTrue(remote.canExecute)
    }

    @Test
    fun partialScope_isExecutableWhenEveryReferencedSourceStillExists() {
        val result = LibraryTaskReplayValidation.validate(
            partialScope(
                localRootUris = listOf("content://tree/primary%3AMovies"),
                remoteIds = listOf("remote-a"),
            ),
            configuredLocalRootUris = listOf(
                "content://tree/primary%3AMovies",
                "content://tree/primary%3ATV",
            ),
            configuredRemoteIds = listOf("remote-a", "remote-b"),
        )

        assertTrue(result.canExecute)
        assertTrue(result.missingLocalRootUris.isEmpty())
        assertTrue(result.missingRemoteIds.isEmpty())
    }

    @Test
    fun partialScope_reportsMissingLocalAndRemoteReferences() {
        val result = LibraryTaskReplayValidation.validate(
            partialScope(
                localRootUris = listOf(
                    "content://tree/primary%3AMovies",
                    "content://tree/primary%3ATV",
                ),
                remoteIds = listOf("remote-a", "remote-b"),
            ),
            configuredLocalRootUris = listOf("content://tree/primary%3AMovies"),
            configuredRemoteIds = listOf("remote-b"),
        )

        assertFalse(result.canExecute)
        assertEquals(listOf("content://tree/primary%3ATV"), result.missingLocalRootUris)
        assertEquals(listOf("remote-a"), result.missingRemoteIds)
    }

    @Test
    fun partialScope_doesNotRequireAnUnreferencedSourceType() {
        val localOnly = LibraryTaskReplayValidation.validate(
            partialScope(localRootUris = listOf("content://tree/primary%3AMovies")),
            configuredLocalRootUris = listOf("content://tree/primary%3AMovies"),
            configuredRemoteIds = emptyList(),
        )
        val remoteOnly = LibraryTaskReplayValidation.validate(
            partialScope(remoteIds = listOf("remote-a")),
            configuredLocalRootUris = emptyList(),
            configuredRemoteIds = listOf("remote-a"),
        )

        assertTrue(localOnly.canExecute)
        assertTrue(remoteOnly.canExecute)
    }

    @Test
    fun invalidReplayScope_isNotExecutableEvenWhenReferencesExist() {
        val unsupported = partialScope(
            localRootUris = listOf("content://tree/primary%3AMovies"),
        ).copy(version = LibraryTaskReplayScope.CURRENT_VERSION + 1)

        val result = LibraryTaskReplayValidation.validate(
            unsupported,
            configuredLocalRootUris = unsupported.localRootUris,
            configuredRemoteIds = emptyList(),
        )

        assertFalse(result.canExecute)
        assertTrue(result.missingLocalRootUris.isEmpty())
        assertTrue(result.missingRemoteIds.isEmpty())
    }

    private fun fullLibraryScope() = LibraryTaskReplayScope(
        fullLibrary = true,
        localRootUris = emptyList(),
        remoteIds = emptyList(),
        rebuild = false,
    )

    private fun partialScope(
        localRootUris: List<String> = emptyList(),
        remoteIds: List<String> = emptyList(),
    ) = LibraryTaskReplayScope(
        fullLibrary = false,
        localRootUris = localRootUris,
        remoteIds = remoteIds,
        rebuild = false,
    )
}
