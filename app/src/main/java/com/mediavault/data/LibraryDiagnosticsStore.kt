package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemoteErrorMessages
import com.mediavault.remote.RemotePath
import com.mediavault.remote.RemoteStreamCache
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
    val scrapeEvidence: Map<String, ScrapeEvidence> = emptyMap(),
    val remoteCapabilities: List<RemoteCapability> = emptyList(),
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
            scrapeEvidence = emptyMap(),
            remoteCapabilities = emptyList(),
        )
    }
}

data class LibraryIssue(
    val kind: String,
    val path: String,
    val title: String,
    val detail: String,
    val evidence: ScrapeEvidence? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("path", path)
        put("title", title)
        put("detail", detail)
        evidence?.let { put("evidence", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): LibraryIssue = LibraryIssue(
            kind = o.optString("kind", ""),
            path = o.optString("path", ""),
            title = o.optString("title", ""),
            detail = o.optString("detail", ""),
            evidence = o.optJSONObject("evidence")?.let { ScrapeEvidence.fromJson(it) },
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

data class RemoteCapability(
    val key: String,
    val sourceId: String,
    val sourceType: String,
    val name: String,
    val relativePath: String,
    val lastCheckedAt: String,
    val trigger: String,
    val canList: Boolean?,
    val canOpen: Boolean?,
    val supportsRange: Boolean?,
    val fileSize: Long,
    val firstByteMs: Long,
    val seekReadMs: Long,
    val cachePrefixBytes: Long,
    val cacheRangeFiles: Int,
    val cacheRangeBytes: Long,
    val cacheTotalBytes: Long,
    val lastErrorKind: String,
    val lastErrorMessage: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("sourceId", sourceId)
        put("sourceType", sourceType)
        put("name", name)
        put("relativePath", relativePath)
        put("lastCheckedAt", lastCheckedAt)
        put("trigger", trigger)
        put("canList", canList ?: JSONObject.NULL)
        put("canOpen", canOpen ?: JSONObject.NULL)
        put("supportsRange", supportsRange ?: JSONObject.NULL)
        put("fileSize", fileSize)
        put("firstByteMs", firstByteMs)
        put("seekReadMs", seekReadMs)
        put("cachePrefixBytes", cachePrefixBytes)
        put("cacheRangeFiles", cacheRangeFiles)
        put("cacheRangeBytes", cacheRangeBytes)
        put("cacheTotalBytes", cacheTotalBytes)
        put("lastErrorKind", lastErrorKind)
        put("lastErrorMessage", lastErrorMessage)
    }

    companion object {
        fun keyFor(sourceId: String, relativePath: String, trigger: String): String =
            listOf(sourceId, relativePath, trigger).joinToString("|")

        fun fromJson(o: JSONObject): RemoteCapability = RemoteCapability(
            key = o.optString("key", ""),
            sourceId = o.optString("sourceId", ""),
            sourceType = o.optString("sourceType", ""),
            name = o.optString("name", ""),
            relativePath = o.optString("relativePath", ""),
            lastCheckedAt = o.optString("lastCheckedAt", "--"),
            trigger = o.optString("trigger", ""),
            canList = o.optNullableBoolean("canList"),
            canOpen = o.optNullableBoolean("canOpen"),
            supportsRange = o.optNullableBoolean("supportsRange"),
            fileSize = o.optLong("fileSize", C.LENGTH_UNSET.toLong()),
            firstByteMs = o.optLong("firstByteMs", -1L),
            seekReadMs = o.optLong("seekReadMs", -1L),
            cachePrefixBytes = o.optLong("cachePrefixBytes", 0L),
            cacheRangeFiles = o.optInt("cacheRangeFiles", 0),
            cacheRangeBytes = o.optLong("cacheRangeBytes", 0L),
            cacheTotalBytes = o.optLong("cacheTotalBytes", 0L),
            lastErrorKind = o.optString("lastErrorKind", ""),
            lastErrorMessage = o.optString("lastErrorMessage", ""),
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
        val scrapeEvidence = readScrapeEvidence(root.optJSONObject("scrapeEvidence") ?: JSONObject())
        val remoteCapabilities = readRemoteCapabilities(root.optJSONArray("remoteCapabilities") ?: JSONArray())
        LibraryDiagnosticsSnapshot(
            scannedAt = root.optString("scannedAt", "--"),
            itemCount = root.optInt("itemCount", 0),
            localCount = root.optInt("localCount", 0),
            remoteCount = root.optInt("remoteCount", 0),
            issueCounts = counts,
            sampleIssues = sampleIssues,
            issues = issues,
            sourceHealth = sourceHealth,
            scrapeEvidence = scrapeEvidence,
            remoteCapabilities = remoteCapabilities,
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

    private fun readScrapeEvidence(obj: JSONObject): Map<String, ScrapeEvidence> {
        val out = linkedMapOf<String, ScrapeEvidence>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            out[path] = ScrapeEvidence.fromJson(obj.optJSONObject(path) ?: continue)
        }
        return out
    }

    private fun readRemoteCapabilities(arr: JSONArray): List<RemoteCapability> {
        val out = mutableListOf<RemoteCapability>()
        for (i in 0 until arr.length()) {
            out.add(RemoteCapability.fromJson(arr.optJSONObject(i) ?: continue))
        }
        return out
    }

    fun scanAndPersist(
        store: MediaStore,
        items: List<MediaItem>,
        probeSources: Boolean = false,
        probeRemoteIds: Set<String>? = null,
        previousSourceHealth: List<SourceHealth> = emptyList(),
        previousRemoteCapabilities: List<RemoteCapability> = emptyList(),
    ): LibraryDiagnosticsSnapshot {
        val snapshot = scan(store, items, probeSources, probeRemoteIds, previousSourceHealth, previousRemoteCapabilities)
        writeSnapshot(snapshot)
        return snapshot
    }

    @Synchronized
    fun recordRemoteCapability(capability: RemoteCapability): LibraryDiagnosticsSnapshot {
        val current = readSnapshot() ?: LibraryDiagnosticsSnapshot.EMPTY
        val merged = (current.remoteCapabilities.filter { it.key != capability.key } + capability)
            .sortedByDescending { it.lastCheckedAt }
            .take(80)
        val next = current.copy(remoteCapabilities = merged)
        writeSnapshot(next)
        return next
    }

    private fun scan(
        store: MediaStore,
        items: List<MediaItem>,
        probeSources: Boolean,
        probeRemoteIds: Set<String>?,
        previousSourceHealth: List<SourceHealth>,
        previousRemoteCapabilities: List<RemoteCapability>,
    ): LibraryDiagnosticsSnapshot {
        val scannedAt = nowText()
        val remotes = store.readRemotesList()
        val remoteById = remotes.associateBy { it.id }
        val remoteIds = remoteById.keys
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
                } else if (remoteById[parsed.configId]?.credentialMissing == true) {
                    val name = remoteById[parsed.configId]?.name?.ifBlank { parsed.configId } ?: parsed.configId
                    issues.add(item.issue("missing_remote_credential", "远程配置“$name”缺少密码，需补凭据后再复检"))
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
        val evidenceByPath = items.associate { it.path to ScrapeEvidence.fromItem(it) }
        return LibraryDiagnosticsSnapshot(
            scannedAt = scannedAt,
            itemCount = items.size,
            localCount = items.count { !RemotePath.isRemote(it.path) },
            remoteCount = items.count { RemotePath.isRemote(it.path) },
            issueCounts = counts,
            sampleIssues = issues.take(20),
            issues = issues,
            sourceHealth = buildSourceHealth(store, items, scannedAt, probeSources, probeRemoteIds, previousSourceHealth, remotes),
            scrapeEvidence = evidenceByPath,
            remoteCapabilities = buildRemoteCapabilities(
                remotes,
                items,
                probeSources,
                probeRemoteIds,
                previousRemoteCapabilities,
            ),
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
        val evidence = JSONObject()
        for ((path, itemEvidence) in snapshot.scrapeEvidence) evidence.put(path, itemEvidence.toJson())
        val remoteCapabilities = JSONArray()
        for (capability in snapshot.remoteCapabilities) remoteCapabilities.put(capability.toJson())
        val root = JSONObject().apply {
            put("schema", 4)
            put("scannedAt", snapshot.scannedAt)
            put("itemCount", snapshot.itemCount)
            put("localCount", snapshot.localCount)
            put("remoteCount", snapshot.remoteCount)
            put("issueCounts", counts)
            put("sampleIssues", sample)
            put("issues", issues)
            put("sourceHealth", sources)
            put("scrapeEvidence", evidence)
            put("remoteCapabilities", remoteCapabilities)
        }
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun buildSourceHealth(
        store: MediaStore,
        items: List<MediaItem>,
        scannedAt: String,
        probeSources: Boolean,
        probeRemoteIds: Set<String>?,
        previous: List<SourceHealth>,
        remotes: List<RemoteConfig> = store.readRemotesList(),
    ): List<SourceHealth> {
        val previousByKey = previous.associateBy { it.sourceType + "|" + it.sourceId }
        val out = mutableListOf<SourceHealth>()
        val probeAll = probeSources && probeRemoteIds == null
        for (uri in store.readLocalRootUris()) {
            val sample = items.firstOrNull { it.path.startsWith(uri) }?.path.orEmpty()
            val prev = previousByKey["local|$uri"]
            out.add(if (probeAll) probeLocal(uri, scannedAt, sample) else carrySource(prev, uri, "local", localName(uri), scannedAt, sample))
        }
        for (cfg in remotes) {
            val sample = items.firstOrNull { RemotePath.parse(it.path)?.configId == cfg.id }?.let {
                RemotePath.parse(it.path)?.relativePath
            }.orEmpty()
            val prev = previousByKey["${cfg.type}|${cfg.id}"] ?: previousByKey["remote|${cfg.id}"]
            val carryPrev = if (!cfg.credentialMissing && prev?.lastErrorKind == "credential_missing") null else prev
            val probeThisRemote = probeSources && (probeRemoteIds == null || cfg.id in probeRemoteIds)
            out.add(
                if (probeThisRemote) {
                    probeRemote(cfg, scannedAt, sample)
                } else {
                    carrySource(carryPrev, cfg.id, cfg.type.ifBlank { "remote" }, cfg.name, scannedAt, sample)
                },
            )
        }
        return out
    }

    private fun buildRemoteCapabilities(
        remotes: List<RemoteConfig>,
        items: List<MediaItem>,
        probeSources: Boolean,
        probeRemoteIds: Set<String>?,
        previous: List<RemoteCapability>,
    ): List<RemoteCapability> {
        val remoteIds = remotes.map { it.id }.toSet()
        val out = mutableListOf<RemoteCapability>()
        for (cfg in remotes) {
            val sample = items.firstOrNull { RemotePath.parse(it.path)?.configId == cfg.id }?.let {
                RemotePath.parse(it.path)?.relativePath
            }.orEmpty()
            val prev = previous.firstOrNull { it.sourceId == cfg.id && it.trigger == "diagnostic" }
            val carryPrev = if (!cfg.credentialMissing && prev?.lastErrorKind == "credential_missing") null else prev
            val probeThisRemote = probeSources && (probeRemoteIds == null || cfg.id in probeRemoteIds)
            out.add(
                if (probeThisRemote) {
                    if (cfg.credentialMissing) credentialMissingCapability(cfg, sample) else probeRemoteCapability(cfg, sample)
                } else {
                    carryRemoteCapability(carryPrev, cfg, sample)
                },
            )
        }
        val playback = previous
            .filter { it.sourceId in remoteIds && it.trigger.startsWith("playback") }
            .takeLast(60)
        return (out + playback).distinctBy { it.key }.take(80)
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
        if (cfg.credentialMissing) {
            return SourceHealth(
                sourceId = cfg.id,
                sourceType = cfg.type.ifBlank { "remote" },
                name = cfg.name,
                lastCheckedAt = checkedAt,
                lastScanAt = scannedAt,
                reachable = false,
                supportsRange = null,
                lastErrorKind = "credential_missing",
                lastErrorMessage = "远程配置缺少密码，请在管理媒体目录补充后再复检",
                samplePath = samplePath,
            )
        }
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

    private fun credentialMissingCapability(cfg: RemoteConfig, samplePath: String): RemoteCapability =
        RemoteCapability(
            key = RemoteCapability.keyFor(cfg.id, samplePath, "diagnostic"),
            sourceId = cfg.id,
            sourceType = cfg.type.ifBlank { "remote" },
            name = cfg.name,
            relativePath = samplePath,
            lastCheckedAt = nowText(),
            trigger = "diagnostic",
            canList = false,
            canOpen = false,
            supportsRange = null,
            fileSize = C.LENGTH_UNSET.toLong(),
            firstByteMs = -1L,
            seekReadMs = -1L,
            cachePrefixBytes = 0L,
            cacheRangeFiles = 0,
            cacheRangeBytes = 0L,
            cacheTotalBytes = 0L,
            lastErrorKind = "credential_missing",
            lastErrorMessage = "远程配置缺少密码，请在管理媒体目录补充后再复检",
        )

    private fun carryRemoteCapability(
        previous: RemoteCapability?,
        cfg: RemoteConfig,
        samplePath: String,
    ): RemoteCapability {
        val rel = samplePath.ifBlank { previous?.relativePath.orEmpty() }
        return previous?.copy(
            key = RemoteCapability.keyFor(cfg.id, rel, previous.trigger),
            sourceId = cfg.id,
            sourceType = cfg.type.ifBlank { "remote" },
            name = cfg.name,
            relativePath = rel,
        ) ?: RemoteCapability(
            key = RemoteCapability.keyFor(cfg.id, rel, "diagnostic"),
            sourceId = cfg.id,
            sourceType = cfg.type.ifBlank { "remote" },
            name = cfg.name,
            relativePath = rel,
            lastCheckedAt = "--",
            trigger = "diagnostic",
            canList = null,
            canOpen = null,
            supportsRange = null,
            fileSize = C.LENGTH_UNSET.toLong(),
            firstByteMs = -1L,
            seekReadMs = -1L,
            cachePrefixBytes = 0L,
            cacheRangeFiles = 0,
            cacheRangeBytes = 0L,
            cacheTotalBytes = 0L,
            lastErrorKind = "unchecked",
            lastErrorMessage = "",
        )
    }

    private fun probeRemoteCapability(cfg: RemoteConfig, samplePath: String): RemoteCapability {
        val checkedAt = nowText()
        val client = runCatching { RemoteClients.create(cfg) }.getOrElse { e ->
            return remoteCapability(
                cfg = cfg,
                relativePath = samplePath,
                checkedAt = checkedAt,
                trigger = "diagnostic",
                canList = false,
                canOpen = false,
                supportsRange = null,
                error = e,
            )
        }
        val entries = runCatching { client.list("") }.getOrElse { e ->
            return remoteCapability(
                cfg = cfg,
                relativePath = samplePath,
                checkedAt = checkedAt,
                trigger = "diagnostic",
                canList = false,
                canOpen = false,
                supportsRange = null,
                error = e,
            )
        }
        val sample = samplePath.ifBlank { entries.firstOrNull { !it.directory }?.path.orEmpty() }
        if (sample.isBlank()) {
            return remoteCapability(
                cfg = cfg,
                relativePath = "",
                checkedAt = checkedAt,
                trigger = "diagnostic",
                canList = true,
                canOpen = null,
                supportsRange = null,
            )
        }
        val size = runCatching { client.fileSize(sample) }.getOrDefault(C.LENGTH_UNSET.toLong())
        val firstRead = measureRemoteRead(client, sample, 0L, 1L)
        if (firstRead.isFailure) {
            return remoteCapability(
                cfg = cfg,
                relativePath = sample,
                checkedAt = checkedAt,
                trigger = "diagnostic",
                canList = true,
                canOpen = false,
                supportsRange = null,
                fileSize = size,
                error = firstRead.exceptionOrNull(),
            )
        }
        val rangeRead = if (size > 1L) measureRemoteRead(client, sample, 1L, 1L) else null
        return remoteCapability(
            cfg = cfg,
            relativePath = sample,
            checkedAt = checkedAt,
            trigger = "diagnostic",
            canList = true,
            canOpen = true,
            supportsRange = rangeRead?.isSuccess,
            fileSize = size,
            firstByteMs = firstRead.getOrDefault(-1L),
            seekReadMs = rangeRead?.getOrDefault(-1L) ?: -1L,
            error = rangeRead?.exceptionOrNull(),
        )
    }

    private fun measureRemoteRead(client: com.mediavault.remote.RemoteClient, relativePath: String, offset: Long, length: Long): Result<Long> =
        runCatching {
            val start = System.currentTimeMillis()
            client.openRead(relativePath, offset, length).use { input ->
                input.read()
            }
            (System.currentTimeMillis() - start).coerceAtLeast(0L)
        }

    private fun remoteCapability(
        cfg: RemoteConfig,
        relativePath: String,
        checkedAt: String,
        trigger: String,
        canList: Boolean?,
        canOpen: Boolean?,
        supportsRange: Boolean?,
        fileSize: Long = C.LENGTH_UNSET.toLong(),
        firstByteMs: Long = -1L,
        seekReadMs: Long = -1L,
        error: Throwable? = null,
    ): RemoteCapability {
        val cache = if (relativePath.isBlank()) {
            RemoteStreamCache.ItemCacheSummary(0L, 0, 0L)
        } else {
            RemoteStreamCache.cacheSummaryForItem(app, cfg, relativePath)
        }
        return RemoteCapability(
            key = RemoteCapability.keyFor(cfg.id, relativePath, trigger),
            sourceId = cfg.id,
            sourceType = cfg.type.ifBlank { "remote" },
            name = cfg.name,
            relativePath = relativePath,
            lastCheckedAt = checkedAt,
            trigger = trigger,
            canList = canList,
            canOpen = canOpen,
            supportsRange = supportsRange,
            fileSize = fileSize,
            firstByteMs = firstByteMs,
            seekReadMs = seekReadMs,
            cachePrefixBytes = cache.prefixBytes,
            cacheRangeFiles = cache.rangeFiles,
            cacheRangeBytes = cache.rangeBytes,
            cacheTotalBytes = cache.totalBytes,
            lastErrorKind = error?.let { it::class.java.simpleName }.orEmpty(),
            lastErrorMessage = error?.let { RemoteErrorMessages.userMessage(app, it) }.orEmpty(),
        )
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
        evidence = ScrapeEvidence.fromItem(this),
    )

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun localName(uri: String): String =
        Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "本机目录"
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)
