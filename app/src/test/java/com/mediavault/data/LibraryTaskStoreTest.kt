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
}
