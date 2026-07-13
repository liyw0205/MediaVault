package com.mediavault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSourceActionPolicyTest {
    @Test
    fun emptySource_cannotOpenMediaOrRescrape() {
        val policy = evaluate(mediaCount = 0, issueCount = 1)

        assertFalse(policy.canOpenMedia)
        assertTrue(policy.canOpenIssues)
        assertTrue(policy.canRecheck)
        assertFalse(policy.canRescrape)
        assertEquals(RemoteSourceActionPolicy.RescrapeBlockReason.NO_MEDIA, policy.rescrapeBlockReason)
    }

    @Test
    fun sourceWithoutIssues_cannotOpenIssues() {
        val policy = evaluate(mediaCount = 1, issueCount = 0)

        assertTrue(policy.canOpenMedia)
        assertFalse(policy.canOpenIssues)
    }

    @Test
    fun missingCredential_canRecheckButCannotRescrape() {
        val policy = evaluate(
            credentialMissing = true,
            mediaCount = 1,
            issueCount = 1,
            reachable = false,
        )

        assertTrue(policy.canRecheck)
        assertFalse(policy.canRescrape)
        assertEquals(
            RemoteSourceActionPolicy.RescrapeBlockReason.CREDENTIAL_MISSING,
            policy.rescrapeBlockReason,
        )
    }

    @Test
    fun missingCredential_takesPriorityOverEmptySourceBlockReason() {
        val policy = evaluate(credentialMissing = true, mediaCount = 0)

        assertEquals(
            RemoteSourceActionPolicy.RescrapeBlockReason.CREDENTIAL_MISSING,
            policy.rescrapeBlockReason,
        )
    }

    @Test
    fun sourceWithMediaAndCredential_canRescrape() {
        val policy = evaluate(mediaCount = 3, issueCount = 0, reachable = true)

        assertTrue(policy.canRescrape)
        assertNull(policy.rescrapeBlockReason)
    }

    @Test
    fun healthDoesNotPreventManualRecheckOrRescrape() {
        listOf(true, false, null).forEach { reachable ->
            val policy = evaluate(mediaCount = 1, reachable = reachable)

            assertTrue(policy.canRecheck)
            assertTrue(policy.canRescrape)
        }
    }

    private fun evaluate(
        credentialMissing: Boolean = false,
        mediaCount: Int = 0,
        issueCount: Int = 0,
        reachable: Boolean? = null,
    ) = RemoteSourceActionPolicy.evaluate(
        credentialMissing = credentialMissing,
        mediaCount = mediaCount,
        issueCount = issueCount,
        reachable = reachable,
    )
}
