package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySourceContextTest {
    @Test
    fun remotePath_prefersRemoteSourceContext() {
        val context = LibrarySourceContext.fromPath(
            "remote|server-1|movies/Arrival.mkv",
            listOf("content://media/external/video/media"),
        )

        assertEquals(LibrarySourceContext.Type.REMOTE_SOURCE, context?.type)
        assertEquals("server-1", context?.key)
    }

    @Test
    fun localPath_usesMostSpecificConfiguredRoot() {
        val context = LibrarySourceContext.fromPath(
            "content://tree/primary%3AMedia/document/primary%3AMedia%2FMovies%2FArrival.mkv",
            listOf("content://tree/primary%3AMedia", "content://tree/primary%3AMedia/document/primary%3AMedia%2FMovies"),
        )

        assertEquals(LibrarySourceContext.Type.LOCAL_ROOT, context?.type)
        assertEquals("content://tree/primary%3AMedia/document/primary%3AMedia%2FMovies", context?.key)
    }

    @Test
    fun unknownPath_hasNoSourceContext() {
        assertNull(LibrarySourceContext.fromPath("content://unknown/item", emptyList()))
    }
}
