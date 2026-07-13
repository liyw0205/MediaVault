package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LibraryTaskStoreTest {
    @Test
    fun taskEntry_roundTripsJsonFields() {
        val entry = LibraryTaskEntry(
            id = "task-1",
            type = LibraryTaskStore.TYPE_IMPORT_BACKUP,
            title = "导入备份包",
            status = LibraryTaskStore.STATUS_PARTIAL,
            createdAt = "2026-07-08 08:00:00",
            updatedAt = "2026-07-08 08:01:00",
            summary = "导入完成",
            detail = "需补远程凭据 2 个",
            issueKind = "missing_remote_credential",
            localScopeCount = 1,
            remoteScopeCount = 3,
            replayScope = LibraryTaskReplayScope(
                fullLibrary = false,
                localRootUris = listOf("content://tree/primary%3AMovies"),
                remoteIds = listOf("remote-a", "remote-b"),
                rebuild = true,
            ),
        )

        val parsed = LibraryTaskEntry.fromJson(entry.toJson())

        assertEquals(entry, parsed)
    }

    @Test
    fun taskEntry_blankIssueKindBecomesNull() {
        val json = LibraryTaskEntry(
            id = "task-2",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "全部增量刮削",
            status = LibraryTaskStore.STATUS_SUCCESS,
            createdAt = "2026-07-08 08:00:00",
            updatedAt = "2026-07-08 08:01:00",
            summary = "完成",
        ).toJson()

        val parsed = LibraryTaskEntry.fromJson(json)

        assertNull(parsed.issueKind)
    }

    @Test
    fun taskEntry_roundTripsStructuredStatistics() {
        val entry = LibraryTaskEntry(
            id = "task-stats",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "增量刮削",
            status = LibraryTaskStore.STATUS_SUCCESS,
            createdAt = "2026-07-13 08:00:00",
            updatedAt = "2026-07-13 08:01:00",
            summary = "完成",
            statistics = LibraryTaskStatistics(12, 10, 2, 1, 8, 4, 3),
        )

        assertEquals(entry, LibraryTaskEntry.fromJson(entry.toJson()))
    }

    @Test
    fun taskEntry_withoutStatistics_usesEmptyStatistics() {
        val parsed = LibraryTaskEntry.fromJson(JSONObject().apply {
            put("id", "legacy-task")
            put("type", LibraryTaskStore.TYPE_SCRAPE)
            put("title", "旧任务")
        })

        assertEquals(LibraryTaskStatistics(), parsed.statistics)
    }

    @Test
    fun taskFailure_redactsCredentialsAndClassifiesTimeout() {
        val message = "timeout https://alice:secret@example.test?token=abc123"

        assertEquals("timeout", LibraryTaskFailure.category(message))
        assertEquals("timeout https://alice:***@example.test?token=***", LibraryTaskFailure.redact(message))
    }

    @Test
    fun replayScope_serializesOnlySourceReferencesAndIntent() {
        val json = LibraryTaskReplayScope(
            fullLibrary = false,
            localRootUris = listOf("content://tree/primary%3AMovies"),
            remoteIds = listOf("remote-a"),
            rebuild = true,
        ).toJson()

        assertEquals(LibraryTaskReplayScope.CURRENT_VERSION, json.getInt("version"))
        assertEquals("content://tree/primary%3AMovies", json.getJSONArray("localRootUris").getString(0))
        assertEquals("remote-a", json.getJSONArray("remoteIds").getString(0))
        assertTrue(json.getBoolean("rebuild"))
        assertFalse(json.has("password"))
        assertFalse(json.has("token"))
        assertFalse(json.has("remoteConfigs"))
    }

    @Test
    fun legacyTask_withoutReplayScope_remainsReadableButNotRetryable() {
        val parsed = LibraryTaskEntry.fromJson(JSONObject().apply {
            put("id", "legacy-task")
            put("type", LibraryTaskStore.TYPE_SCRAPE)
            put("status", LibraryTaskStore.STATUS_FAILED)
            put("title", "旧局部刮削")
            put("localScopeCount", 1)
            put("remoteScopeCount", 1)
        })

        assertNull(parsed.replayScope)
        assertEquals(1, parsed.localScopeCount)
        assertEquals(1, parsed.remoteScopeCount)
        assertFalse(LibraryTaskStore.canSafelyRetry(parsed))
    }

    @Test
    fun failedScrape_withExplicitReplayScope_isRetryable() {
        val retryable = LibraryTaskEntry(
            id = "retryable",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "局部增量刮削",
            status = LibraryTaskStore.STATUS_FAILED,
            createdAt = "--",
            updatedAt = "--",
            summary = "失败",
            localScopeCount = 1,
            remoteScopeCount = 1,
            replayScope = LibraryTaskReplayScope(
                fullLibrary = false,
                localRootUris = listOf("content://tree/primary%3AMovies"),
                remoteIds = listOf("remote-a"),
                rebuild = false,
            ),
        )

        assertTrue(LibraryTaskStore.canSafelyRetry(retryable))
        assertTrue(LibraryTaskStore.canSafelyRetry(retryable.copy(status = LibraryTaskStore.STATUS_PARTIAL)))
        assertTrue(LibraryTaskStore.canSafelyRetry(retryable.copy(status = LibraryTaskStore.STATUS_CANCELLED)))
        assertFalse(LibraryTaskStore.canSafelyRetry(retryable.copy(status = LibraryTaskStore.STATUS_SUCCESS)))
        assertFalse(LibraryTaskStore.canSafelyRetry(retryable.copy(type = LibraryTaskStore.TYPE_IMPORT_BACKUP)))
    }

    @Test
    fun replayEligibility_rejectsUnsupportedOrEmptyScope() {
        val task = LibraryTaskEntry(
            id = "not-retryable",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "刮削",
            status = LibraryTaskStore.STATUS_FAILED,
            createdAt = "--",
            updatedAt = "--",
            summary = "失败",
        )

        assertFalse(LibraryTaskStore.canSafelyRetry(task))
        assertFalse(
            LibraryTaskStore.canSafelyRetry(
                task.copy(
                    replayScope = LibraryTaskReplayScope(
                        fullLibrary = false,
                        localRootUris = emptyList(),
                        remoteIds = emptyList(),
                        rebuild = false,
                    ),
                ),
            ),
        )
        assertFalse(
            LibraryTaskStore.canSafelyRetry(
                task.copy(
                    replayScope = LibraryTaskReplayScope(
                        version = LibraryTaskReplayScope.CURRENT_VERSION + 1,
                        fullLibrary = false,
                        localRootUris = listOf("content://tree/primary%3AMovies"),
                        remoteIds = emptyList(),
                        rebuild = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun fullLibraryReplayScope_isExplicitAndRetryable() {
        val task = LibraryTaskEntry(
            id = "full-library",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "全部重新刮削",
            status = LibraryTaskStore.STATUS_CANCELLED,
            createdAt = "--",
            updatedAt = "--",
            summary = "已取消",
            replayScope = LibraryTaskReplayScope(
                fullLibrary = true,
                localRootUris = emptyList(),
                remoteIds = emptyList(),
                rebuild = true,
            ),
        )

        assertTrue(LibraryTaskStore.canSafelyRetry(task))
        assertTrue(task.replayScope?.rebuild == true)
    }
}
