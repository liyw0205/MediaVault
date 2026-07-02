package com.mediavault.data

import android.content.Context
import com.mediavault.remote.RemotePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LibraryDiagnosticsSnapshot(
    val scannedAt: String,
    val itemCount: Int,
    val localCount: Int,
    val remoteCount: Int,
    val issueCounts: Map<String, Int>,
    val sampleIssues: List<LibraryIssue>,
    val issues: List<LibraryIssue> = sampleIssues,
) {
    val totalIssues: Int = issueCounts.values.sum()

    companion object {
        val EMPTY = LibraryDiagnosticsSnapshot(
            scannedAt = "--",
            itemCount = 0,
            localCount = 0,
            remoteCount = 0,
            issueCounts = emptyMap(),
            sampleIssues = emptyList(),
            issues = emptyList(),
        )
    }
}

data class LibraryIssue(
    val kind: String,
    val path: String,
    val title: String,
    val detail: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("path", path)
        put("title", title)
        put("detail", detail)
    }

    companion object {
        fun fromJson(o: JSONObject): LibraryIssue = LibraryIssue(
            kind = o.optString("kind", ""),
            path = o.optString("path", ""),
            title = o.optString("title", ""),
            detail = o.optString("detail", ""),
        )
    }
}

class LibraryDiagnosticsStore(context: Context) {
    private val app = context.applicationContext
    private val file: File
        get() = File(app.filesDir, "mediavault/library-diagnostics.json").also { it.parentFile?.mkdirs() }

    fun readSnapshot(): LibraryDiagnosticsSnapshot? = runCatching {
        if (!file.isFile) return@runCatching null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val countsObj = root.optJSONObject("issueCounts") ?: JSONObject()
        val counts = mutableMapOf<String, Int>()
        val keys = countsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            counts[key] = countsObj.optInt(key, 0)
        }
        val issues = readIssues(root.optJSONArray("issues") ?: root.optJSONArray("sampleIssues") ?: JSONArray())
        val sampleIssues = readIssues(root.optJSONArray("sampleIssues") ?: JSONArray()).ifEmpty { issues.take(20) }
        LibraryDiagnosticsSnapshot(
            scannedAt = root.optString("scannedAt", "--"),
            itemCount = root.optInt("itemCount", 0),
            localCount = root.optInt("localCount", 0),
            remoteCount = root.optInt("remoteCount", 0),
            issueCounts = counts,
            sampleIssues = sampleIssues,
            issues = issues,
        )
    }.getOrNull()

    private fun readIssues(arr: JSONArray): List<LibraryIssue> {
        val issues = mutableListOf<LibraryIssue>()
        for (i in 0 until arr.length()) {
            issues.add(LibraryIssue.fromJson(arr.optJSONObject(i) ?: continue))
        }
        return issues
    }

    fun scanAndPersist(store: MediaStore, items: List<MediaItem>): LibraryDiagnosticsSnapshot {
        val snapshot = scan(store, items)
        writeSnapshot(snapshot)
        return snapshot
    }

    private fun scan(store: MediaStore, items: List<MediaItem>): LibraryDiagnosticsSnapshot {
        val remoteIds = store.readRemotesList().map { it.id }.toSet()
        val pathCounts = items.groupingBy { it.path }.eachCount()
        val titleCounts = items
            .mapNotNull { normalizedTitleKey(it)?.let { key -> key to it } }
            .groupingBy { it.first }
            .eachCount()
        val issues = mutableListOf<LibraryIssue>()

        for (item in items) {
            if (item.path.isBlank()) {
                issues.add(item.issue("missing_path", "条目没有可播放路径"))
                continue
            }
            if ((pathCounts[item.path] ?: 0) > 1) {
                issues.add(item.issue("duplicate_path", "相同路径在库中出现多次"))
            }
            val titleKey = normalizedTitleKey(item)
            if (titleKey != null && (titleCounts[titleKey] ?: 0) > 1) {
                issues.add(item.issue("duplicate_title", "标题、年份、季集疑似重复"))
            }
            if (RemotePath.isRemote(item.path)) {
                val parsed = RemotePath.parse(item.path)
                if (parsed == null || parsed.configId !in remoteIds) {
                    issues.add(item.issue("stale_remote", "远程配置不存在或已删除"))
                }
            }
            if (item.coverLocalPath().isNullOrBlank()) {
                issues.add(item.issue("missing_cover", "没有封面缓存或旁挂封面"))
            }
            if (isUnmatched(item)) {
                issues.add(item.issue("unmatched", "未看到 NFO 或 TMDB 匹配依据"))
            } else if (isLowConfidenceMatch(item)) {
                issues.add(item.issue("low_confidence_match", "TMDB 匹配置信度较低，建议复核"))
            }
        }

        val counts = issues.groupingBy { it.kind }.eachCount().toSortedMap()
        return LibraryDiagnosticsSnapshot(
            scannedAt = nowText(),
            itemCount = items.size,
            localCount = items.count { !RemotePath.isRemote(it.path) },
            remoteCount = items.count { RemotePath.isRemote(it.path) },
            issueCounts = counts,
            sampleIssues = issues.take(20),
            issues = issues,
        )
    }

    private fun writeSnapshot(snapshot: LibraryDiagnosticsSnapshot) {
        val counts = JSONObject()
        for ((kind, count) in snapshot.issueCounts) counts.put(kind, count)
        val sample = JSONArray()
        for (issue in snapshot.sampleIssues) sample.put(issue.toJson())
        val issues = JSONArray()
        for (issue in snapshot.issues) issues.put(issue.toJson())
        val root = JSONObject().apply {
            put("schema", 1)
            put("scannedAt", snapshot.scannedAt)
            put("itemCount", snapshot.itemCount)
            put("localCount", snapshot.localCount)
            put("remoteCount", snapshot.remoteCount)
            put("issueCounts", counts)
            put("sampleIssues", sample)
            put("issues", issues)
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun normalizedTitleKey(item: MediaItem): String? {
        val title = item.displayTitle().trim().lowercase(Locale.ROOT)
        if (title.isBlank()) return null
        val season = item.raw.optString("season", "").trim()
        val episode = item.raw.optString("episode", "").trim()
        return listOf(title, item.year.trim(), season, episode).joinToString("|")
    }

    private fun isUnmatched(item: MediaItem): Boolean {
        val raw = item.raw
        val nfo = raw.optJSONObject("nfo")
        val hasNfo = nfo != null && nfo.length() > 0
        val tmdbId = raw.optString("tmdb_id", raw.optString("tmdbId", "")).trim()
        val tmdbSummary = raw.optString("tmdb_match_confidence", "").trim()
        return !hasNfo && tmdbId.isBlank() && tmdbSummary.isBlank()
    }

    private fun isLowConfidenceMatch(item: MediaItem): Boolean {
        val raw = item.raw
        val confidence = raw.optString("tmdb_match_confidence", "").lowercase(Locale.ROOT)
        return raw.optBoolean("tmdb_weak", false) ||
            raw.optString("tmdb_match_reason", "").contains("热度") ||
            raw.optString("tmdb_match", "").contains("弱") ||
            confidence in setOf("weak", "low", "popularity", "fallback")
    }

    private fun MediaItem.issue(kind: String, detail: String): LibraryIssue = LibraryIssue(
        kind = kind,
        path = path,
        title = displayTitle(),
        detail = detail,
    )

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
