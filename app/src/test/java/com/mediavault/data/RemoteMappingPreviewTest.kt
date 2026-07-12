package com.mediavault.data

import com.mediavault.remote.RemoteConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteMappingPreviewTest {
    @Test
    fun endpointChange_countsOnlyItemsForEditedRemote() {
        val old = RemoteConfig("remote-a", "webdav", "old.example", 443, "u", "p", "/dav", "A")
        val next = old.copy(host = "new.example")
        val item = MediaItem("remote|remote-a|films/A.mkv", "A", "A", emptyList(), emptyList(), "", 0, "", "", "", "", JSONObject())
        val other = item.copy(path = "remote|remote-b|films/B.mkv")

        val preview = RemoteMappingPreview.create(old, next, listOf(item, other))

        assertEquals(1, preview.affectedCount)
        assertEquals(listOf("A"), preview.samplePaths)
    }
}
