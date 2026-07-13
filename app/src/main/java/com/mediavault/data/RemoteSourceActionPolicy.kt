package com.mediavault.data

data class RemoteSourceActionPolicy(
    val canOpenMedia: Boolean,
    val canOpenIssues: Boolean,
    val canRecheck: Boolean,
    val canRescrape: Boolean,
    val rescrapeBlockReason: RescrapeBlockReason?,
) {
    enum class RescrapeBlockReason {
        CREDENTIAL_MISSING,
        NO_MEDIA,
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun evaluate(
            credentialMissing: Boolean,
            mediaCount: Int,
            issueCount: Int,
            reachable: Boolean?,
        ): RemoteSourceActionPolicy {
            val rescrapeBlockReason = when {
                credentialMissing -> RescrapeBlockReason.CREDENTIAL_MISSING
                mediaCount <= 0 -> RescrapeBlockReason.NO_MEDIA
                else -> null
            }

            return RemoteSourceActionPolicy(
                canOpenMedia = mediaCount > 0,
                canOpenIssues = issueCount > 0,
                canRecheck = true,
                canRescrape = rescrapeBlockReason == null,
                rescrapeBlockReason = rescrapeBlockReason,
            )
        }
    }
}
