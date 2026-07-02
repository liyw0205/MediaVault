package com.mediavault.data

import android.content.Context
import android.os.Build
import com.mediavault.remote.RemoteStreamCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ExportArchive(
    val file: File,
    val suggestedName: String,
    val summary: String,
)

class ExportBundleWriter(context: Context) {
    private val app = context.applicationContext
    private val store = MediaStore(app)

    fun createBackupArchive(repository: LibraryRepository): ExportArchive {
        val snapshot = refreshSnapshot(repository)
        val archive = createArchiveFile("backup")
        val entries = mutableListOf<ExportEntry>()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putLibrary(entries)
            zip.putFile(entries, "data/roots.list", store.rootsListFile)
            zip.putText(entries, "data/remotes.redacted.json", redactedRemotesJson().toString(2))
            zip.putFile(entries, "data/scrape-record.tsv", store.scrapeRecordFile)
            zip.putFile(entries, "data/library-diagnostics.json", diagnosticsFile())
            zip.putText(entries, "data/playback-progress.json", sharedPrefsJson("mediavault_playback_progress").toString(2))
            zip.putText(entries, "data/history.json", sharedPrefsJson("mediavault_history").toString(2))
            zip.putText(entries, "config/scrape-settings.redacted.json", scrapeSettingsJson().toString(2))
            zip.putText(entries, "config/subtitle-prefs.json", sharedPrefsJson("subtitle_prefs").toString(2))
            zip.putText(entries, "config/playback-ui.json", sharedPrefsJson("playback_ui").toString(2))
            zip.putText(entries, "manifest.json", manifestJson("backup", snapshot, entries).toString(2))
        }
        val validation = validateArchiveOrDelete(archive, "backup", BACKUP_REQUIRED_ENTRIES)
        return ExportArchive(
            file = archive,
            suggestedName = archive.name,
            summary = "备份包已生成：${snapshot.itemCount} 条媒体、${store.readLocalRootUris().size} 个本机目录、${store.readRemotesList().size} 个远程配置；远程密码和 TMDB Key 已脱敏。${validation.summary}",
        )
    }

    fun createDiagnosticsArchive(repository: LibraryRepository): ExportArchive {
        val snapshot = refreshSnapshot(repository)
        val archive = createArchiveFile("diagnostics")
        val entries = mutableListOf<ExportEntry>()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putText(entries, "diagnostics/summary.json", diagnosticsSummaryJson(repository, snapshot).toString(2))
            zip.putFile(entries, "diagnostics/library-diagnostics.json", diagnosticsFile())
            zip.putText(entries, "config/remotes-summary.json", remotesSummaryJson().toString(2))
            zip.putText(entries, "config/local-roots-summary.json", localRootsSummaryJson().toString(2))
            zip.putText(entries, "config/scrape-settings.redacted.json", scrapeSettingsJson().toString(2))
            zip.putText(entries, "manifest.json", manifestJson("diagnostics", snapshot, entries).toString(2))
        }
        val validation = validateArchiveOrDelete(archive, "diagnostics", DIAGNOSTICS_REQUIRED_ENTRIES)
        return ExportArchive(
            file = archive,
            suggestedName = archive.name,
            summary = "诊断包已生成：库 ${snapshot.itemCount} 条、问题 ${snapshot.totalIssues} 个、源状态 ${snapshot.sourceHealth.size} 条；不包含明文密码。${validation.summary}",
        )
    }

    private fun refreshSnapshot(repository: LibraryRepository): LibraryDiagnosticsSnapshot {
        runCatching { repository.reload() }
        return runCatching { repository.refreshDiagnostics(probeSources = false) }
            .getOrElse { repository.reloadDiagnosticsSnapshot() }
    }

    private fun createArchiveFile(kind: String): File {
        val dir = File(app.cacheDir, "mediavault_exports").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > 24L * 60L * 60L * 1000L) {
                file.delete()
            }
        }
        return File(dir, "MediaVault_${appVersionName()}_${kind}_${fileStamp()}.zip")
    }

    private fun diagnosticsFile(): File =
        File(app.filesDir, "mediavault/library-diagnostics.json")

    private fun emptyLibraryJson(): JSONObject =
        JSONObject().apply {
            put("ok", true)
            put("items", JSONArray())
            put("updated", nowText())
        }

    private fun manifestJson(
        kind: String,
        snapshot: LibraryDiagnosticsSnapshot,
        entries: List<ExportEntry>,
    ): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("kind", kind)
        put("createdAt", nowText())
        put("app", appJson())
        put("device", deviceJson())
        put("redaction", JSONObject().apply {
            put("remotePasswords", true)
            put("tmdbApiKey", true)
            put("includedCredentials", false)
        })
        put("library", JSONObject().apply {
            put("itemCount", snapshot.itemCount)
            put("localCount", snapshot.localCount)
            put("remoteCount", snapshot.remoteCount)
            put("totalIssues", snapshot.totalIssues)
            put("scannedAt", snapshot.scannedAt)
            put("issueCounts", JSONObject(snapshot.issueCounts))
        })
        put("contents", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("path", entry.path)
                    put("bytes", entry.bytes)
                })
            }
        })
        put("notes", if (kind == "backup") {
            "Core library/config/progress export. Cover cache and remote playback cache are excluded and can be regenerated."
        } else {
            "Diagnostic export. Uses the latest persisted source health and does not actively probe network sources."
        })
    }

    private fun diagnosticsSummaryJson(
        repository: LibraryRepository,
        snapshot: LibraryDiagnosticsSnapshot,
    ): JSONObject {
        val data = repository.dataSizes()
        val cache = RemoteStreamCache.cacheBreakdown(app)
        return JSONObject().apply {
            put("generatedAt", nowText())
            put("app", appJson())
            put("device", deviceJson())
            put("library", JSONObject().apply {
                put("itemCount", snapshot.itemCount)
                put("localCount", snapshot.localCount)
                put("remoteCount", snapshot.remoteCount)
                put("scannedAt", snapshot.scannedAt)
                put("totalIssues", snapshot.totalIssues)
                put("issueCounts", JSONObject(snapshot.issueCounts))
            })
            put("config", JSONObject().apply {
                put("localRootCount", store.readLocalRootUris().size)
                put("remoteCount", store.readRemotesList().size)
                put("scrapeSettings", scrapeSettingsJson())
                put("remoteCache", JSONObject().apply {
                    put("prefixFiles", cache.prefixFiles)
                    put("prefixBytes", cache.prefixBytes)
                    put("rangeFiles", cache.rangeFiles)
                    put("rangeBytes", cache.rangeBytes)
                    put("maxPerFileBytes", cache.maxPerFileBytes)
                    put("maxTotalBytes", cache.maxTotalBytes)
                })
                put("dataSizes", JSONObject().apply {
                    put("libraryBytes", data.libraryBytes)
                    put("coverBytes", data.coverBytes)
                    put("coverCount", data.coverCount)
                    put("scrapeRecordBytes", data.scrapeRecordBytes)
                    put("remoteStreamFiles", data.remoteStreamFiles)
                    put("remoteStreamBytes", data.remoteStreamBytes)
                })
            })
            put("sourceHealth", JSONArray().apply {
                snapshot.sourceHealth.forEach { put(it.toJson()) }
            })
            put("remoteCapabilities", JSONArray().apply {
                snapshot.remoteCapabilities.forEach { put(it.toJson()) }
            })
            put("recentErrors", recentErrorsJson(snapshot))
        }
    }

    private fun recentErrorsJson(snapshot: LibraryDiagnosticsSnapshot): JSONArray =
        JSONArray().apply {
            snapshot.sourceHealth
                .filter { it.lastErrorKind.isNotBlank() || it.lastErrorMessage.isNotBlank() }
                .forEach { source ->
                    put(JSONObject().apply {
                        put("type", "sourceHealth")
                        put("sourceId", source.sourceId)
                        put("sourceType", source.sourceType)
                        put("name", source.name)
                        put("at", source.lastCheckedAt)
                        put("errorKind", source.lastErrorKind)
                        put("errorMessage", source.lastErrorMessage)
                    })
                }
            snapshot.remoteCapabilities
                .filter { it.lastErrorKind.isNotBlank() || it.lastErrorMessage.isNotBlank() }
                .sortedByDescending { it.lastCheckedAt }
                .take(40)
                .forEach { capability ->
                    put(JSONObject().apply {
                        put("type", "remoteCapability")
                        put("sourceId", capability.sourceId)
                        put("sourceType", capability.sourceType)
                        put("name", capability.name)
                        put("relativePath", capability.relativePath)
                        put("trigger", capability.trigger)
                        put("at", capability.lastCheckedAt)
                        put("errorKind", capability.lastErrorKind)
                        put("errorMessage", capability.lastErrorMessage)
                    })
                }
        }

    private fun redactedRemotesJson(): JSONArray =
        runCatching {
            val arr = JSONArray(store.readRemotesJsonText())
            JSONArray().apply {
                for (i in 0 until arr.length()) {
                    val source = arr.optJSONObject(i) ?: JSONObject()
                    val redacted = redactJson(source)
                    if (source.optBoolean("credentialMissing", false) && source.optString("password", "").isBlank()) {
                        redacted.put("credentialMissing", true)
                        redacted.put("passwordRedacted", true)
                    }
                    put(redacted)
                }
            }
        }.getOrDefault(JSONArray())

    private fun remotesSummaryJson(): JSONArray =
        JSONArray().apply {
            store.readRemotesList().forEach { remote ->
                put(JSONObject().apply {
                    put("id", remote.id)
                    put("type", remote.type)
                    put("name", remote.name)
                    put("host", remote.host)
                    put("port", remote.port)
                    put("basePath", remote.basePath)
                    put("userConfigured", remote.user.isNotBlank())
                    put("passwordConfigured", remote.password.isNotBlank())
                    put("passwordRedacted", remote.password.isNotBlank() || remote.credentialMissing)
                    put("credentialMissing", remote.credentialMissing)
                })
            }
        }

    private fun localRootsSummaryJson(): JSONObject =
        JSONObject().apply {
            val roots = store.readLocalRootUris()
            put("count", roots.size)
            put("roots", JSONArray().apply { roots.forEach { put(it) } })
        }

    private fun scrapeSettingsJson(): JSONObject {
        val s = ScrapeConfig.readSettings(app)
        return JSONObject().apply {
            put("scrapeMode", s.scrapeMode)
            put("threadCount", s.threadCount)
            put("remoteFrameConcurrency", s.remoteFrameConcurrency)
            put("coverFromFiles", s.coverFromFiles)
            put("coverFromVideoFrame", s.coverFromVideoFrame)
            put("metadataFromNfo", s.metadataFromNfo)
            put("metadataFromFilename", s.metadataFromFilename)
            put("scanSidecarSubtitles", s.scanSidecarSubtitles)
            put("remoteCacheMaxBytesPerFile", s.remoteCacheMaxBytesPerFile)
            put("remoteCacheMaxTotalBytes", s.remoteCacheMaxTotalBytes)
            put("tmdbApiKey", "")
            put("tmdbApiKeyRedacted", s.tmdbApiKey.isNotBlank())
        }
    }

    private fun sharedPrefsJson(name: String): JSONObject {
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entries = JSONObject()
        prefs.all.toSortedMap().forEach { (key, value) ->
            entries.put(key, value.toJsonValue())
        }
        return JSONObject().apply {
            put("name", name)
            put("entryCount", prefs.all.size)
            put("entries", entries)
        }
    }

    private fun appJson(): JSONObject =
        JSONObject().apply {
            put("packageName", app.packageName)
            put("versionName", appVersionName())
            put("versionCode", appVersionCode())
        }

    private fun deviceJson(): JSONObject =
        JSONObject().apply {
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE ?: "")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("brand", Build.BRAND ?: "")
            put("model", Build.MODEL ?: "")
        }

    private fun appVersionName(): String =
        packageInfo()?.versionName?.takeIf { it.isNotBlank() } ?: "unknown"

    private fun appVersionCode(): Long {
        val info = packageInfo() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun packageInfo() =
        runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()

    private fun redactJson(obj: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (isSensitiveKey(key)) {
                out.put(key, "")
                out.put("${key}Redacted", value != null && value != JSONObject.NULL && value.toString().isNotBlank())
            } else {
                out.put(key, when (value) {
                    is JSONObject -> redactJson(value)
                    is JSONArray -> redactJson(value)
                    else -> value
                })
            }
        }
        return out
    }

    private fun redactJson(arr: JSONArray): JSONArray =
        JSONArray().apply {
            for (i in 0 until arr.length()) {
                when (val value = arr.opt(i)) {
                    is JSONObject -> put(redactJson(value))
                    is JSONArray -> put(redactJson(value))
                    else -> put(value)
                }
            }
        }

    private fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase(Locale.ROOT)
        return k == "password" || k.contains("apikey") || k.contains("api_key") ||
            k.contains("token") || k.contains("secret")
    }

    private fun Any?.toJsonValue(): Any = when (this) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it ?: JSONObject.NULL) } }
        else -> this
    }

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private data class ExportEntry(val path: String, val bytes: Long)
    private data class ArchiveValidationResult(val entryCount: Int, val manifestContentCount: Int) {
        val summary: String = "已校验 $entryCount 个 ZIP 条目 / 清单 $manifestContentCount 项。"
    }

    private data class ReadZipEntry(val bytes: Long, val text: String?)

    private fun validateArchiveOrDelete(
        archive: File,
        expectedKind: String,
        requiredEntries: Set<String>,
    ): ArchiveValidationResult =
        runCatching { validateArchive(archive, expectedKind, requiredEntries) }
            .getOrElse { e ->
                archive.delete()
                throw e
            }

    private fun validateArchive(
        archive: File,
        expectedKind: String,
        requiredEntries: Set<String>,
    ): ArchiveValidationResult {
        val entryBytes = linkedMapOf<String, Long>()
        var manifestText: String? = null
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (!entry.isDirectory) {
                        if (entryBytes.containsKey(path)) validationError("ZIP 内有重复条目：$path")
                        val read = zip.readEntry(captureText = path == "manifest.json")
                        entryBytes[path] = read.bytes
                        if (path == "manifest.json") manifestText = read.text.orEmpty()
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            validationError("无法读取 ZIP：${e.message?.take(120).orEmpty().ifBlank { e.javaClass.simpleName }}")
        }

        requiredEntries.forEach { path ->
            if (!entryBytes.containsKey(path)) validationError("缺少必要条目：$path")
        }

        val rawManifest = manifestText ?: validationError("缺少 manifest.json")
        val manifest = runCatching { JSONObject(rawManifest) }
            .getOrElse { validationError("manifest.json 不是有效 JSON") }
        val schema = manifest.optInt("schema", 0)
        if (schema <= 0) validationError("manifest.json 缺少有效 schema")
        val kind = manifest.optString("kind", "")
        if (kind != expectedKind) validationError("manifest.kind=$kind，期望 $expectedKind")

        val contents = manifest.optJSONArray("contents")
            ?: validationError("manifest.json 缺少 contents 清单")
        val manifestPaths = linkedSetOf<String>()
        for (i in 0 until contents.length()) {
            val obj = contents.optJSONObject(i)
                ?: validationError("contents[$i] 不是有效对象")
            val path = obj.optString("path", "")
            if (path.isBlank()) validationError("contents[$i] 缺少 path")
            if (!manifestPaths.add(path)) validationError("contents 有重复条目：$path")
            val actualBytes = entryBytes[path] ?: validationError("contents 条目不存在于 ZIP：$path")
            val declaredBytes = obj.optLong("bytes", -1L)
            if (declaredBytes < 0L) validationError("contents 条目缺少 bytes：$path")
            if (actualBytes != declaredBytes) {
                validationError("contents 字节数不匹配：$path")
            }
        }
        val unlisted = entryBytes.keys.filter { it != "manifest.json" && !manifestPaths.contains(it) }
        if (unlisted.isNotEmpty()) {
            validationError("ZIP 存在未列入清单的条目：${unlisted.take(3).joinToString("、")}")
        }
        return ArchiveValidationResult(entryBytes.size, manifestPaths.size)
    }

    private fun validationError(message: String): Nothing =
        throw IllegalStateException("导出包校验失败：$message")

    private fun ZipInputStream.readEntry(captureText: Boolean): ReadZipEntry {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val captured = if (captureText) ByteArrayOutputStream() else null
        var bytes = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            bytes += read
            if (captured != null) {
                if (captured.size() + read > MAX_MANIFEST_BYTES) {
                    validationError("manifest.json 超过大小上限")
                }
                captured.write(buffer, 0, read)
            }
        }
        return ReadZipEntry(bytes, captured?.let { String(it.toByteArray(), Charsets.UTF_8) })
    }

    private fun ZipOutputStream.putLibrary(entries: MutableList<ExportEntry>) {
        if (store.libraryFile.isFile) {
            putFile(entries, "data/library.json", store.libraryFile)
        } else {
            putText(entries, "data/library.json", emptyLibraryJson().toString(2))
        }
    }

    private fun ZipOutputStream.putFile(entries: MutableList<ExportEntry>, path: String, file: File) {
        if (!file.isFile) return
        putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
        entries += ExportEntry(path, file.length())
    }

    private fun ZipOutputStream.putText(entries: MutableList<ExportEntry>, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
        entries += ExportEntry(path, bytes.size.toLong())
    }

    private companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private val BACKUP_REQUIRED_ENTRIES = setOf(
            "manifest.json",
            "data/library.json",
            "data/remotes.redacted.json",
            "config/scrape-settings.redacted.json",
        )
        private val DIAGNOSTICS_REQUIRED_ENTRIES = setOf(
            "manifest.json",
            "diagnostics/summary.json",
            "config/remotes-summary.json",
            "config/scrape-settings.redacted.json",
        )
    }
}
