package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        val parsed = LibraryTaskEntry.fromJson(org.json.JSONObject().apply {
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
    fun fullLibraryFailedScrape_isTheOnlyRetryableTaskWithoutReplayScope() {
        val retryable = LibraryTaskEntry(
            id = "retryable",
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = "全部增量刮削",
            status = LibraryTaskStore.STATUS_FAILED,
            createdAt = "--",
            updatedAt = "--",
            summary = "失败",
        )

        assertEquals(true, LibraryTaskStore.canSafelyRetry(retryable))
        assertEquals(false, LibraryTaskStore.canSafelyRetry(retryable.copy(remoteScopeCount = 1)))
        assertEquals(false, LibraryTaskStore.canSafelyRetry(retryable.copy(type = LibraryTaskStore.TYPE_IMPORT_BACKUP)))
    }
}
