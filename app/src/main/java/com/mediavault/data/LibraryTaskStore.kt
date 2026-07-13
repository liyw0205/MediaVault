package com.mediavault.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LibraryTaskStatistics(
    val discoveredCount: Int = 0,
    val writtenCount: Int = 0,
    val skippedCount: Int = 0,
    val issueCount: Int = 0,
    val tmdbHitCount: Int = 0,
    val tmdbMissCount: Int = 0,
    val coverAddedCount: Int = 0,
) {
    fun isEmpty(): Boolean = listOf(
        discoveredCount, writtenCount, skippedCount, issueCount,
        tmdbHitCount, tmdbMissCount, coverAddedCount,
    ).all { it == 0 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("discoveredCount", discoveredCount)
        put("writtenCount", writtenCount)
        put("skippedCount", skippedCount)
        put("issueCount", issueCount)
        put("tmdbHitCount", tmdbHitCount)
        put("tmdbMissCount", tmdbMissCount)
        put("coverAddedCount", coverAddedCount)
    }

    companion object {
        fun fromJson(value: JSONObject?): LibraryTaskStatistics = LibraryTaskStatistics(
            discoveredCount = value?.optInt("discoveredCount", 0) ?: 0,
            writtenCount = value?.optInt("writtenCount", 0) ?: 0,
            skippedCount = value?.optInt("skippedCount", 0) ?: 0,
            issueCount = value?.optInt("issueCount", 0) ?: 0,
            tmdbHitCount = value?.optInt("tmdbHitCount", 0) ?: 0,
            tmdbMissCount = value?.optInt("tmdbMissCount", 0) ?: 0,
            coverAddedCount = value?.optInt("coverAddedCount", 0) ?: 0,
        )
    }
}

data class LibraryTaskReplayScope(
    val version: Int = CURRENT_VERSION,
    val fullLibrary: Boolean,
    val localRootUris: List<String>,
    val remoteIds: List<String>,
    val rebuild: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("fullLibrary", fullLibrary)
        put("localRootUris", JSONArray(localRootUris))
        put("remoteIds", JSONArray(remoteIds))
        put("rebuild", rebuild)
    }

    fun isReplayable(): Boolean =
        version == CURRENT_VERSION &&
            (fullLibrary || localRootUris.isNotEmpty() || remoteIds.isNotEmpty()) &&
            !(fullLibrary && (localRootUris.isNotEmpty() || remoteIds.isNotEmpty())) &&
            localRootUris.all { it.isNotBlank() } &&
            remoteIds.all { it.isNotBlank() }

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(value: JSONObject?): LibraryTaskReplayScope? {
            if (value == null) return null
            return LibraryTaskReplayScope(
                version = value.optInt("version", 0),
                fullLibrary = value.optBoolean("fullLibrary", false),
                localRootUris = value.optJSONArray("localRootUris").toStringList(),
                remoteIds = value.optJSONArray("remoteIds").toStringList(),
                rebuild = value.optBoolean("rebuild", false),
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index ->
                optString(index, "").takeIf { it.isNotBlank() }
            }
        }
    }
}

data class LibraryTaskEntry(
    val id: String,
    val type: String,
    val title: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val summary: String,
    val detail: String = "",
    val issueKind: String? = null,
    val localScopeCount: Int = 0,
    val remoteScopeCount: Int = 0,
    val statistics: LibraryTaskStatistics = LibraryTaskStatistics(),
    val failureCategory: String? = null,
    val failureSummary: String? = null,
    val replayScope: LibraryTaskReplayScope? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("title", title)
        put("status", status)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("summary", summary)
        put("detail", detail)
        put("issueKind", issueKind ?: "")
        put("localScopeCount", localScopeCount)
        put("remoteScopeCount", remoteScopeCount)
        put("statistics", statistics.toJson())
        put("failureCategory", failureCategory ?: "")
        put("failureSummary", failureSummary ?: "")
        replayScope?.let { put("replayScope", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): LibraryTaskEntry = LibraryTaskEntry(
            id = o.optString("id", ""),
            type = o.optString("type", ""),
            title = o.optString("title", ""),
            status = o.optString("status", LibraryTaskStore.STATUS_SUCCESS),
            createdAt = o.optString("createdAt", "--"),
            updatedAt = o.optString("updatedAt", "--"),
            summary = o.optString("summary", ""),
            detail = o.optString("detail", ""),
            issueKind = o.optString("issueKind", "").takeIf { it.isNotBlank() },
            localScopeCount = o.optInt("localScopeCount", 0),
            remoteScopeCount = o.optInt("remoteScopeCount", 0),
            statistics = LibraryTaskStatistics.fromJson(o.optJSONObject("statistics")),
            failureCategory = o.optString("failureCategory", "").takeIf { it.isNotBlank() },
            failureSummary = o.optString("failureSummary", "").takeIf { it.isNotBlank() },
            replayScope = LibraryTaskReplayScope.fromJson(o.optJSONObject("replayScope")),
        )
    }
}

class LibraryTaskStore(context: Context) {
    private val file: File = File(context.applicationContext.filesDir, "mediavault/library-tasks.json")

    fun read(): List<LibraryTaskEntry> = synchronized(LOCK) {
        readUnlocked()
    }

    fun latest(): LibraryTaskEntry? = read().firstOrNull()

    fun recordStarted(
        type: String,
        title: String,
        summary: String,
        detail: String = "",
        issueKind: String? = null,
        localScopeCount: Int = 0,
        remoteScopeCount: Int = 0,
        statistics: LibraryTaskStatistics = LibraryTaskStatistics(),
        replayScope: LibraryTaskReplayScope? = null,
    ): String = synchronized(LOCK) {
        val now = nowText()
        val entries = readUnlocked()
        val id = newId(entries)
        val entry = LibraryTaskEntry(
            id = id,
            type = type,
            title = title,
            status = STATUS_RUNNING,
            createdAt = now,
            updatedAt = now,
            summary = summary,
            detail = detail,
            issueKind = issueKind,
            localScopeCount = localScopeCount,
            remoteScopeCount = remoteScopeCount,
            statistics = statistics,
            replayScope = replayScope,
        )
        writeUnlocked(listOf(entry) + entries.filterNot { it.id == id })
        id
    }

    fun recordFinished(
        type: String,
        title: String,
        status: String,
        summary: String,
        detail: String = "",
        issueKind: String? = null,
        localScopeCount: Int = 0,
        remoteScopeCount: Int = 0,
        statistics: LibraryTaskStatistics = LibraryTaskStatistics(),
        replayScope: LibraryTaskReplayScope? = null,
    ): String = synchronized(LOCK) {
        val now = nowText()
        val entries = readUnlocked()
        val id = newId(entries)
        val entry = LibraryTaskEntry(
            id = id,
            type = type,
            title = title,
            status = status,
            createdAt = now,
            updatedAt = now,
            summary = summary,
            detail = detail,
            issueKind = issueKind,
            localScopeCount = localScopeCount,
            remoteScopeCount = remoteScopeCount,
            statistics = statistics,
            replayScope = replayScope,
        )
        writeUnlocked(listOf(entry) + entries.filterNot { it.id == id })
        id
    }

    fun finish(
        id: String,
        status: String,
        summary: String,
        detail: String = "",
        issueKind: String? = null,
        statistics: LibraryTaskStatistics? = null,
        failureCategory: String? = null,
        failureSummary: String? = null,
    ) {
        if (id.isBlank()) return
        synchronized(LOCK) {
            val now = nowText()
            val next = readUnlocked().map { entry ->
                if (entry.id == id) {
                    entry.copy(
                        status = status,
                        updatedAt = now,
                        summary = summary,
                        detail = detail.ifBlank { entry.detail },
                        issueKind = issueKind ?: entry.issueKind,
                        statistics = statistics ?: entry.statistics,
                        failureCategory = failureCategory ?: entry.failureCategory,
                        failureSummary = failureSummary?.let(LibraryTaskFailure::redact) ?: entry.failureSummary,
                    )
                } else {
                    entry
                }
            }
            writeUnlocked(next)
        }
    }

    fun clearFinished(): Int = synchronized(LOCK) {
        val entries = readUnlocked()
        val kept = entries.filter { it.status == STATUS_RUNNING }
        writeUnlocked(kept)
        entries.size - kept.size
    }

    private fun readUnlocked(): List<LibraryTaskEntry> = runCatching {
        if (!file.isFile) return emptyList()
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("tasks") ?: JSONArray()
        (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.let { LibraryTaskEntry.fromJson(it) } }
            .filter { it.id.isNotBlank() }
    }.getOrElse { emptyList() }

    private fun writeUnlocked(entries: List<LibraryTaskEntry>) {
        file.parentFile?.mkdirs()
        val trimmed = entries.take(MAX_ENTRIES)
        val root = JSONObject().apply {
            put("schema", 1)
            put("tasks", JSONArray().apply {
                trimmed.forEach { put(it.toJson()) }
            })
        }
        file.writeText(root.toString(2))
    }

    private fun newId(entries: List<LibraryTaskEntry>): String {
        val taken = entries.mapTo(mutableSetOf()) { it.id }
        val base = "task-${System.currentTimeMillis()}"
        if (base !in taken) return base
        var index = 1
        while ("$base-$index" in taken) index++
        return "$base-$index"
    }

    companion object {
        const val TYPE_SCRAPE = "scrape"
        const val TYPE_SOURCE_RECHECK = "source_recheck"
        const val TYPE_IMPORT_BACKUP = "import_backup"
        const val TYPE_BATCH_REMOVE = "batch_remove"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val MAX_ENTRIES = 50
        private val LOCK = Any()

        fun nowText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        fun canSafelyRetry(task: LibraryTaskEntry): Boolean =
            task.type == TYPE_SCRAPE &&
                task.status in setOf(STATUS_FAILED, STATUS_PARTIAL, STATUS_CANCELLED) &&
                task.replayScope?.isReplayable() == true
    }
}
