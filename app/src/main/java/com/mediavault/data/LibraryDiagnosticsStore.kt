package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemoteErrorMessages
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
    val sourceHealth: List<SourceHealth> = emptyList(),
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
            sourceHealth = emptyList(),
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

data class SourceHealth(
    val sourceId: String,
    val sourceType: String,
    val name: String,
    val lastCheckedAt: String,
    val lastScanAt: String,
    val reachable: Boolean?,
    val supportsRange: Boolean?,
    val lastErrorKind: String,
    val lastErrorMessage: String,
    val samplePath: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sourceId", sourceId)
        put("sourceType", sourceType)
        put("name", name)
        put("lastCheckedAt", lastCheckedAt)
        put("lastScanAt", lastScanAt)
        put("reachable", reachable ?: JSONObject.NULL)
        put("supportsRange", supportsRange ?: JSONObject.NULL)
        put("lastErrorKind", lastErrorKind)
        put("lastErrorMessage", lastErrorMessage)
        put("samplePath", samplePath)
    }

    companion object {
        fun fromJson(o: JSONObject): SourceHealth = SourceHealth(
            sourceId = o.optString("sourceId", ""),
            sourceType = o.optString("sourceType", ""),
            name = o.optString("name", ""),
            lastCheckedAt = o.optString("lastCheckedAt", "--"),
            lastScanAt = o.optString("lastScanAt", "--"),
            reachable = o.optNullableBoolean("reachable"),
            supportsRange = o.optNullableBoolean("supportsRange"),
            lastErrorKind = o.optString("lastErrorKind", ""),
            lastErrorMessage = o.optString("lastErrorMessage", ""),
            samplePath = o.optString("samplePath", ""),
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
        val sourceHealth = readSourceHealth(root.optJSONArray("sourceHealth") ?: JSONArray())
        LibraryDiagnosticsSnapshot(
            scannedAt = root.optString("scannedAt", "--"),
            itemCount = root.optInt("itemCount", 0),
            localCount = root.optInt("localCount", 0),
            remoteCount = root.optInt("remoteCount", 0),
            issueCounts = counts,
            sampleIssues = sampleIssues,
            issues = issues,
            sourceHealth = sourceHealth,
        )
    }.getOrNull()

    private fun readIssues(arr: JSONArray): List<LibraryIssue> {
        val issues = mutableListOf<LibraryIssue>()
        for (i in 0 until arr.length()) {
            issues.add(LibraryIssue.fromJson(arr.optJSONObject(i) ?: continue))
        }
        return issues
    }

    private fun readSourceHealth(arr: JSONArray): List<SourceHealth> {
        val out = mutableListOf<SourceHealth>()
        for (i in 0 until arr.length()) {
            out.add(SourceHealth.fromJson(arr.optJSONObject(i) ?: continue))
        }
        return out
    }

    fun scanAndPersist(
        store: MediaStore,
        items: List<MediaItem>,
        probeSources: Boolean = false,
        previousSourceHealth: List<SourceHealth> = emptyList(),
    ): LibraryDiagnosticsSnapshot {
        val snapshot = scan(store, items, probeSources, previousSourceHealth)
        writeSnapshot(snapshot)
        return snapshot
    }

    private fun scan(
        store: MediaStore,
        items: List<MediaItem>,
        probeSources: Boolean,
        previousSourceHealth: List<SourceHealth>,
    ): LibraryDiagnosticsSnapshot {
        val scannedAt = nowText()
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
            scannedAt = scannedAt,
            itemCount = items.size,
            localCount = items.count { !RemotePath.isRemote(it.path) },
            remoteCount = items.count { RemotePath.isRemote(it.path) },
            issueCounts = counts,
            sampleIssues = issues.take(20),
            issues = issues,
            sourceHealth = buildSourceHealth(store, items, scannedAt, probeSources, previousSourceHealth),
        )
    }

    private fun writeSnapshot(snapshot: LibraryDiagnosticsSnapshot) {
        val counts = JSONObject()
        for ((kind, count) in snapshot.issueCounts) counts.put(kind, count)
        val sample = JSONArray()
        for (issue in snapshot.sampleIssues) sample.put(issue.toJson())
        val issues = JSONArray()
        for (issue in snapshot.issues) issues.put(issue.toJson())
        val sources = JSONArray()
        for (source in snapshot.sourceHealth) sources.put(source.toJson())
        val root = JSONObject().apply {
            put("schema", 2)
            put("scannedAt", snapshot.scannedAt)
            put("itemCount", snapshot.itemCount)
            put("localCount", snapshot.localCount)
            put("remoteCount", snapshot.remoteCount)
            put("issueCounts", counts)
            put("sampleIssues", sample)
            put("issues", issues)
            put("sourceHealth", sources)
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun buildSourceHealth(
        store: MediaStore,
        items: List<MediaItem>,
        scannedAt: String,
        probeSources: Boolean,
        previous: List<SourceHealth>,
    ): List<SourceHealth> {
        val previousByKey = previous.associateBy { it.sourceType + "|" + it.sourceId }
        val out = mutableListOf<SourceHealth>()
        for (uri in store.readLocalRootUris()) {
            val sample = items.firstOrNull { it.path.startsWith(uri) }?.path.orEmpty()
            val prev = previousByKey["local|$uri"]
            out.add(if (probeSources) probeLocal(uri, scannedAt, sample) else carrySource(prev, uri, "local", localName(uri), scannedAt, sample))
        }
        for (cfg in store.readRemotesList()) {
            val sample = items.firstOrNull { RemotePath.parse(it.path)?.configId == cfg.id }?.let {
                RemotePath.parse(it.path)?.relativePath
            }.orEmpty()
            val prev = previousByKey["${cfg.type}|${cfg.id}"] ?: previousByKey["remote|${cfg.id}"]
            out.add(if (probeSources) probeRemote(cfg, scannedAt, sample) else carrySource(prev, cfg.id, cfg.type.ifBlank { "remote" }, cfg.name, scannedAt, sample))
        }
        return out
    }

    private fun carrySource(
        previous: SourceHealth?,
        sourceId: String,
        sourceType: String,
        name: String,
        scannedAt: String,
        samplePath: String,
    ): SourceHealth = previous?.copy(
        sourceId = sourceId,
        sourceType = sourceType,
        name = name,
        lastScanAt = scannedAt,
        samplePath = samplePath.ifBlank { previous.samplePath },
    ) ?: SourceHealth(
        sourceId = sourceId,
        sourceType = sourceType,
        name = name,
        lastCheckedAt = "--",
        lastScanAt = scannedAt,
        reachable = null,
        supportsRange = null,
        lastErrorKind = "unchecked",
        lastErrorMessage = "",
        samplePath = samplePath,
    )

    private fun probeLocal(uri: String, scannedAt: String, samplePath: String): SourceHealth {
        val checkedAt = nowText()
        return runCatching {
            val doc = DocumentFile.fromTreeUri(app, Uri.parse(uri))
            val ok = doc != null && doc.exists() && doc.canRead()
            SourceHealth(
                sourceId = uri,
                sourceType = "local",
                name = localName(uri),
                lastCheckedAt = checkedAt,
                lastScanAt = scannedAt,
                reachable = ok,
                supportsRange = null,
                lastErrorKind = if (ok) "" else "not_reachable",
                lastErrorMessage = if (ok) "" else "本机目录不可达或授权已失效",
                samplePath = samplePath,
            )
        }.getOrElse { e ->
            SourceHealth(
                sourceId = uri,
                sourceType = "local",
                name = localName(uri),
                lastCheckedAt = checkedAt,
                lastScanAt = scannedAt,
                reachable = false,
                supportsRange = null,
                lastErrorKind = e::class.java.simpleName,
                lastErrorMessage = e.message.orEmpty(),
                samplePath = samplePath,
            )
        }
    }

    private fun probeRemote(cfg: RemoteConfig, scannedAt: String, samplePath: String): SourceHealth {
        val checkedAt = nowText()
        return runCatching {
            val client = RemoteClients.create(cfg)
            val entries = client.list("")
            val fallbackSample = entries.firstOrNull { !it.directory }?.path.orEmpty()
            val sample = samplePath.ifBlank { fallbackSample }
            val supportsRange = if (sample.isBlank()) {
                null
            } else {
                val size = runCatching { client.fileSize(sample) }.getOrDefault(C.LENGTH_UNSET.toLong())
                if (size in 0L..1L) null else runCatching {
                    client.openRead(sample, 1L, 1L).use { input ->
                        input.read()
                    }
                    true
                }.getOrDefault(false)
            }
            SourceHealth(
                sourceId = cfg.id,
                sourceType = cfg.type.ifBlank { "remote" },
                name = cfg.name,
                lastCheckedAt = checkedAt,
                lastScanAt = scannedAt,
                reachable = true,
                supportsRange = supportsRange,
                lastErrorKind = "",
                lastErrorMessage = "",
                samplePath = sample,
            )
        }.getOrElse { e ->
            SourceHealth(
                sourceId = cfg.id,
                sourceType = cfg.type.ifBlank { "remote" },
                name = cfg.name,
                lastCheckedAt = checkedAt,
                lastScanAt = scannedAt,
                reachable = false,
                supportsRange = null,
                lastErrorKind = e::class.java.simpleName,
                lastErrorMessage = RemoteErrorMessages.userMessage(app, e),
                samplePath = samplePath,
            )
        }
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

    private fun localName(uri: String): String =
        Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "本机目录"
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)
