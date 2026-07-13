package com.mediavault.data

import com.mediavault.remote.RemoteConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMappingPreviewTest {
    @Test
    fun unchanged_whenOnlyNonMappingFieldsChange() {
        val previous = remote()
        val next = previous.copy(
            user = "next-user",
            password = "next-password",
            name = "Next name",
            credentialMissing = true,
        )

        val preview = RemoteMappingPreview.create(previous, next, listOf(item("remote-a", "A")))

        assertSame(RemoteMappingPreview.Unchanged, preview)
        assertEquals(emptyList<RemoteMappingPreview.ChangeField>(), preview.changes)
        assertEquals(0, preview.affectedCount)
        assertEquals(emptyList<String>(), preview.samplePaths)
        assertFalse(preview.needsConfirmation)
        assertEquals(null, preview.policy)
    }

    @Test
    fun affected_reportsEveryChangedMappingFieldInContractOrder() {
        val previous = remote()
        val next = previous.copy(type = "ftp", host = "new.example", port = 21, basePath = "/media")

        val preview = RemoteMappingPreview.create(previous, next, emptyList())

        assertTrue(preview is RemoteMappingPreview.Affected)
        assertEquals(
            listOf(
                RemoteMappingPreview.ChangeField.PROTOCOL,
                RemoteMappingPreview.ChangeField.HOST,
                RemoteMappingPreview.ChangeField.PORT,
                RemoteMappingPreview.ChangeField.BASE_PATH,
            ),
            preview.changes,
        )
    }

    @Test
    fun affected_reportsEachMappingFieldIndividually() {
        val previous = remote()
        val cases = listOf(
            previous.copy(type = "smb") to RemoteMappingPreview.ChangeField.PROTOCOL,
            previous.copy(host = "new.example") to RemoteMappingPreview.ChangeField.HOST,
            previous.copy(port = 8443) to RemoteMappingPreview.ChangeField.PORT,
            previous.copy(basePath = "/media") to RemoteMappingPreview.ChangeField.BASE_PATH,
        )

        cases.forEach { (next, expectedChange) ->
            val preview = RemoteMappingPreview.create(previous, next, emptyList())

            assertEquals(listOf(expectedChange), preview.changes)
        }
    }

    @Test
    fun affected_countsOnlyRecordsLinkedToPreviousRemoteId() {
        val previous = remote()
        val next = previous.copy(id = "remote-renamed", host = "new.example")
        val matching = item("remote-a", "A")
        val sameNextIdOnly = item("remote-renamed", "Renamed")
        val otherRemote = item("remote-b", "B")
        val local = matching.copy(path = "/local/C.mkv", title = "C")
        val malformed = matching.copy(path = "remote|remote-a", title = "Malformed")

        val preview = RemoteMappingPreview.create(
            previous,
            next,
            listOf(matching, sameNextIdOnly, otherRemote, local, malformed),
        )

        assertEquals(1, preview.affectedCount)
        assertEquals(listOf("A"), preview.samplePaths)
    }

    @Test
    fun affected_capsSamplesAtThreeAndUsesDisplayTitles() {
        val previous = remote()
        val items = listOf(
            item("remote-a", "Title 1", titleCn = "中文 1"),
            item("remote-a", "Title 2", nfoTitle = "NFO 2"),
            item("remote-a", "Title 3"),
            item("remote-a", "Title 4"),
        )

        val preview = RemoteMappingPreview.create(previous, previous.copy(host = "new.example"), items)

        assertEquals(4, preview.affectedCount)
        assertEquals(listOf("中文 1", "NFO 2", "Title 3"), preview.samplePaths)
    }

    @Test
    fun mappingChangeWithNoLinkedRecords_isStillAffectedAndRequiresRecheck() {
        val previous = remote()

        val preview = RemoteMappingPreview.create(previous, previous.copy(basePath = "/media"), emptyList())

        assertTrue(preview is RemoteMappingPreview.Affected)
        assertEquals(0, preview.affectedCount)
        assertTrue(preview.needsConfirmation)
        assertEquals(
            RemoteMappingPreview.UpdatePolicy.RECHECK_WITHOUT_PATH_REWRITE,
            preview.policy,
        )
    }

    private fun remote() = RemoteConfig(
        id = "remote-a",
        type = "webdav",
        host = "old.example",
        port = 443,
        user = "user",
        password = "password",
        basePath = "/dav",
        name = "A",
    )

    private fun item(
        remoteId: String,
        title: String,
        titleCn: String = "",
        nfoTitle: String = "",
    ): MediaItem = MediaItem(
        path = "remote|$remoteId|films/$title.mkv",
        title = title,
        cleanTitle = title,
        tags = emptyList(),
        genres = emptyList(),
        year = "",
        size = 0,
        modified = "",
        nfoTitle = nfoTitle,
        plot = "",
        collection = "",
        raw = JSONObject().apply { put("title_cn", titleCn) },
    )
}
