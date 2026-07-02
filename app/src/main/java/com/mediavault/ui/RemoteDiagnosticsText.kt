package com.mediavault.ui

import android.content.Context
import com.mediavault.R
import com.mediavault.data.RemoteCapability

object RemoteDiagnosticsText {
    fun capabilityText(context: Context, capabilities: List<RemoteCapability>): String {
        val latest = capabilities
            .filter { it.sourceId.isNotBlank() }
            .groupBy { "${it.sourceId}|${it.relativePath}" }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.lastCheckedAt } }
        if (latest.isEmpty()) return context.getString(R.string.remote_capability_empty)

        val open = latest.count { it.canOpen == true }
        val range = latest.count { it.supportsRange == true }
        val limited = latest.count {
            it.canList == false || it.canOpen == false || it.supportsRange == false
        }
        val summary = context.getString(
            R.string.remote_capability_summary_fmt,
            latest.size,
            open,
            range,
            limited,
        )
        val cache = context.getString(
            R.string.remote_capability_cache_fmt,
            LibraryUi.formatBytes(latest.sumOf { it.cachePrefixBytes }),
            latest.sumOf { it.cacheRangeFiles },
            LibraryUi.formatBytes(latest.sumOf { it.cacheRangeBytes }),
        )
        val issueLines = latest
            .filter { it.canList == false || it.canOpen == false || it.supportsRange == false }
            .sortedByDescending { it.lastCheckedAt }
            .take(2)
            .joinToString("\n") { capability ->
                val rel = capability.relativePath.ifBlank { "-" }
                val reason = capability.lastErrorMessage.ifBlank {
                    if (capability.supportsRange == false) {
                        context.getString(R.string.remote_capability_range_no)
                    } else {
                        capability.lastErrorKind.ifBlank { "-" }
                    }
                }
                context.getString(
                    R.string.remote_capability_issue_fmt,
                    capability.name.ifBlank { capability.sourceId },
                    rel,
                    reason,
                )
            }
        return if (issueLines.isBlank()) {
            "$summary\n$cache"
        } else {
            "$summary\n$cache\n$issueLines"
        }
    }
}
