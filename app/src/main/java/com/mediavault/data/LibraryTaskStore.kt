package com.mediavault.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    }
}
