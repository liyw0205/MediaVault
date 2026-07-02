package com.mediavault.data

import android.content.Context
import android.net.Uri
import com.mediavault.remote.RemoteConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupImportPrecheck(
    val createdAt: String,
    val sourceVersionName: String,
    val itemCount: Int,
    val localRootCount: Int,
    val remoteCount: Int,
    val redactedRemotePasswordCount: Int,
    val preservedRemotePasswordCount: Int,
    val missingRemotePasswordCount: Int,
    val missingRemoteCredentials: List<BackupMissingRemoteCredential>,
    val tmdbKeyRedacted: Boolean,
    val tmdbKeyWillBePreserved: Boolean,
    val warnings: List<String>,
)

data class BackupMissingRemoteCredential(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
)

data class BackupImportResult(
    val itemCount: Int,
    val localRootCount: Int,
    val remoteCount: Int,
    val redactedRemotePasswords: Int,
    val restoredRemotePasswords: Int,
    val missingRemotePasswords: Int,
    val missingRemoteCredentials: List<BackupMissingRemoteCredential>,
    val restoredTmdbKey: Boolean,
    val tmdbKeyStillMissing: Boolean,
    val rollbackSnapshotPath: String,
)

data class BackupRollbackSnapshot(
    val name: String,
    val createdAt: String,
    val bytes: Long,
    val lastModified: Long,
)

class BackupImportRolledBackException(cause: Throwable) :
    Exception("导入失败，已自动回滚：${cause.message.orEmpty()}", cause)

class BackupImportManager(context: Context) {
    private val app = context.applicationContext
    private val store = MediaStore(app)

    fun inspect(uri: Uri): BackupImportPrecheck {
        val extracted = extractZip("import_precheck", IMPORT_ALLOWED_ENTRIES) {
            app.contentResolver.openInputStream(uri) ?: error("无法打开备份包")
        }
        return try {
            parsePrecheck(extracted)
        } finally {
            extracted.delete()
        }
    }

    fun importBackup(uri: Uri, repository: LibraryRepository): BackupImportResult {
        val extracted = extractZip("import_apply", IMPORT_ALLOWED_ENTRIES) {
            app.contentResolver.openInputStream(uri) ?: error("无法打开备份包")
        }
        val rollbackSnapshot = createRollbackSnapshot()
        return try {
            val precheck = parsePrecheck(extracted)
            val remotes = prepareRemotesForImport(extracted.textRequired("data/remotes.redacted.json"))
            val scrapeSettings = prepareScrapeSettingsForImport(extracted.textRequired("config/scrape-settings.redacted.json"))

            copyRequired(extracted, "data/library.json", store.libraryFile)
            copyOptional(extracted, "data/roots.list", store.rootsListFile, deleteIfMissing = true)
            writeText(store.remotesFile, remotes.text)
            copyOptional(extracted, "data/scrape-record.tsv", store.scrapeRecordFile, deleteIfMissing = true)
            copyOptional(extracted, "data/library-diagnostics.json", diagnosticsFile(), deleteIfMissing = true)
            writeText(scrapeConfigFile(), scrapeSettings.text)
            extracted.text("data/playback-progress.json")?.let { applySharedPrefs("mediavault_playback_progress", it) }
            extracted.text("data/history.json")?.let { applySharedPrefs("mediavault_history", it) }
            extracted.text("config/subtitle-prefs.json")?.let { applySharedPrefs("subtitle_prefs", it) }
            extracted.text("config/playback-ui.json")?.let { applySharedPrefs("playback_ui", it) }

            repository.reloadDiagnosticsSnapshot()
            repository.reload().getOrThrow()
            repository.refreshDiagnostics(probeSources = false)
            cleanupOldRollbackSnapshots()
            BackupImportResult(
                itemCount = precheck.itemCount,
                localRootCount = precheck.localRootCount,
                remoteCount = precheck.remoteCount,
                redactedRemotePasswords = precheck.redactedRemotePasswordCount,
                restoredRemotePasswords = remotes.restoredPasswords,
                missingRemotePasswords = precheck.missingRemotePasswordCount,
                missingRemoteCredentials = precheck.missingRemoteCredentials,
                restoredTmdbKey = scrapeSettings.restoredTmdbKey,
                tmdbKeyStillMissing = precheck.tmdbKeyRedacted && !scrapeSettings.restoredTmdbKey,
                rollbackSnapshotPath = rollbackSnapshot.absolutePath,
            )
        } catch (e: Throwable) {
            runCatching { restoreRollbackSnapshot(rollbackSnapshot, repository) }
                .onFailure { rollbackError ->
                    throw IllegalStateException(
                        "导入失败，且自动回滚失败：${e.message.orEmpty()}；${rollbackError.message.orEmpty()}",
                        e,
                    )
                }
            throw BackupImportRolledBackException(e)
        } finally {
            extracted.delete()
        }
    }

    fun listRollbackSnapshots(): List<BackupRollbackSnapshot> =
        rollbackDir()
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.map { file ->
                BackupRollbackSnapshot(
                    name = file.name,
                    createdAt = readRollbackCreatedAt(file) ?: formatFileTime(file.lastModified()),
                    bytes = file.length(),
                    lastModified = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun clearRollbackSnapshots(): Int {
        var count = 0
        rollbackDir().listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".zip") && file.delete()) {
                count++
            }
        }
        return count
    }

    fun restoreRollbackSnapshot(snapshotName: String, repository: LibraryRepository): BackupRollbackSnapshot {
        val snapshot = rollbackDir()
            .listFiles()
            ?.firstOrNull { it.isFile && it.name == snapshotName && it.name.endsWith(".zip") }
            ?: error("找不到回滚快照：$snapshotName")
        val info = BackupRollbackSnapshot(
            name = snapshot.name,
            createdAt = readRollbackCreatedAt(snapshot) ?: formatFileTime(snapshot.lastModified()),
            bytes = snapshot.length(),
            lastModified = snapshot.lastModified(),
        )
        restoreRollbackSnapshot(snapshot, repository)
        return info
    }

    private fun parsePrecheck(extracted: ExtractedZip): BackupImportPrecheck {
        val manifest = JSONObject(extracted.textRequired("manifest.json"))
        val kind = manifest.optString("kind", "")
        if (kind != "backup") {
            error("这不是备份包，不能导入：kind=$kind")
        }
        val libraryText = extracted.textRequired("data/library.json")
        val library = MediaLibrary.parse(libraryText, "import")
        val remotesText = extracted.textRequired("data/remotes.redacted.json")
        val remotes = RemoteConfig.listFromJson(remotesText)
        extracted.textRequired("config/scrape-settings.redacted.json")

        val localRootCount = extracted.text("data/roots.list")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.count { it.isNotBlank() && !it.startsWith("#") }
            ?: 0
        val currentPasswords = currentRemotePasswordsById()
        val importedRemotes = JSONArray(remotesText)
        var redactedPasswords = 0
        var preservedPasswords = 0
        val missingCredentials = mutableListOf<BackupMissingRemoteCredential>()
        for (i in 0 until importedRemotes.length()) {
            val remote = importedRemotes.optJSONObject(i) ?: continue
            if (remote.optBoolean("passwordRedacted", false) && remote.optString("password", "").isBlank()) {
                redactedPasswords++
                if (!currentPasswords[remote.optString("id", "")].isNullOrBlank()) {
                    preservedPasswords++
                } else {
                    missingCredentials += BackupMissingRemoteCredential(
                        id = remote.optString("id", ""),
                        name = remote.optString("name", remote.optString("id", "remote")),
                        type = remote.optString("type", "webdav").lowercase(),
                        host = remote.optString("host", ""),
                    )
                }
            }
        }
        val missingPasswords = missingCredentials.size
        val scrapeSettings = JSONObject(extracted.textRequired("config/scrape-settings.redacted.json"))
        val tmdbRedacted = scrapeSettings.optBoolean("tmdbApiKeyRedacted", false) &&
            scrapeSettings.optString("tmdbApiKey", "").isBlank()
        val tmdbPreserved = tmdbRedacted && ScrapeConfig.readSettings(app).tmdbApiKey.isNotBlank()
        val warnings = mutableListOf<String>()
        if (redactedPasswords > 0) {
            warnings += "远程密码已脱敏：$redactedPasswords 个；可保留当前密码 $preservedPasswords 个，需导入后手动补 $missingPasswords 个。"
        }
        if (tmdbRedacted) {
            warnings += if (tmdbPreserved) {
                "TMDB Key 已脱敏，导入时会保留当前 Key。"
            } else {
                "TMDB Key 已脱敏，导入后需要重新填写。"
            }
        }
        if (extracted.file("data/playback-progress.json") == null) {
            warnings += "备份包缺少播放进度，导入时不会覆盖当前播放进度。"
        }
        return BackupImportPrecheck(
            createdAt = manifest.optString("createdAt", "--"),
            sourceVersionName = manifest.optJSONObject("app")?.optString("versionName", "--") ?: "--",
            itemCount = library.items.size,
            localRootCount = localRootCount,
            remoteCount = remotes.size,
            redactedRemotePasswordCount = redactedPasswords,
            preservedRemotePasswordCount = preservedPasswords,
            missingRemotePasswordCount = missingPasswords,
            missingRemoteCredentials = missingCredentials,
            tmdbKeyRedacted = tmdbRedacted,
            tmdbKeyWillBePreserved = tmdbPreserved,
            warnings = warnings,
        )
    }

    private fun prepareRemotesForImport(text: String): PreparedRemotes {
        val currentPasswords = currentRemotePasswordsById()
        val arr = JSONArray(text)
        val out = JSONArray()
        var restored = 0
        for (i in 0 until arr.length()) {
            val remote = JSONObject(arr.getJSONObject(i).toString())
            val id = remote.optString("id", "")
            val redacted = remote.optBoolean("passwordRedacted", false)
            if (redacted && remote.optString("password", "").isBlank()) {
                val current = currentPasswords[id].orEmpty()
                if (current.isNotBlank()) {
                    remote.put("password", current)
                    restored++
                }
            }
            remote.remove("passwordRedacted")
            out.put(remote)
        }
        RemoteConfig.listFromJson(out.toString())
        return PreparedRemotes(out.toString(2), restored)
    }

    private fun prepareScrapeSettingsForImport(text: String): PreparedScrapeSettings {
        val settings = JSONObject(text)
        var restoredTmdbKey = false
        if (settings.optBoolean("tmdbApiKeyRedacted", false) && settings.optString("tmdbApiKey", "").isBlank()) {
            val currentKey = ScrapeConfig.readSettings(app).tmdbApiKey
            if (currentKey.isNotBlank()) {
                settings.put("tmdbApiKey", currentKey)
                restoredTmdbKey = true
            }
        }
        settings.remove("tmdbApiKeyRedacted")
        return PreparedScrapeSettings(settings.toString(2), restoredTmdbKey)
    }

    private fun createRollbackSnapshot(): File {
        val dir = rollbackDir().also { it.mkdirs() }
        val file = File(dir, "MediaVault_import_rollback_${fileStamp()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((path, source) in rollbackFiles()) {
                zip.putFile(path, source)
            }
            for (prefName in PREF_NAMES) {
                zip.putText("prefs/$prefName.json", sharedPrefsJson(prefName).toString(2))
            }
            zip.putText(
                "manifest.json",
                JSONObject()
                    .put("schema", 1)
                    .put("kind", "internal_rollback")
                    .put("createdAt", nowText())
                    .toString(2),
            )
        }
        return file
    }

    private fun restoreRollbackSnapshot(snapshot: File, repository: LibraryRepository) {
        val extracted = extractZip("rollback_restore", ROLLBACK_ALLOWED_ENTRIES) { snapshot.inputStream() }
        try {
            for ((path, target) in rollbackFiles()) {
                val source = extracted.file(path)
                if (source != null) copyFile(source, target) else target.delete()
            }
            for (prefName in PREF_NAMES) {
                extracted.text("prefs/$prefName.json")?.let { applySharedPrefs(prefName, it) }
            }
            repository.reloadDiagnosticsSnapshot()
            repository.reload().getOrThrow()
            repository.refreshDiagnostics(probeSources = false)
        } finally {
            extracted.delete()
        }
    }

    private fun extractZip(
        label: String,
        allowedEntries: Set<String>,
        input: () -> InputStream,
    ): ExtractedZip {
        val dir = File(app.cacheDir, "mediavault_import/$label-${System.currentTimeMillis()}").also {
            it.mkdirs()
        }
        val files = linkedMapOf<String, File>()
        var totalBytes = 0L
        input().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entry != null) {
                    val name = entry.name.orEmpty()
                    validateEntryName(name)
                    if (!entry.isDirectory && name in allowedEntries) {
                        if (name in files) error("备份包里包含重复条目：$name")
                        val outFile = File(dir, name.replace('/', '_'))
                        var entryBytes = 0L
                        outFile.outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                entryBytes += n.toLong()
                                totalBytes += n.toLong()
                                if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                    error("备份包过大，已停止导入")
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                        files[name] = outFile
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return ExtractedZip(dir, files)
    }

    private fun validateEntryName(name: String) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.split('/').any { it == ".." }) {
            error("备份包包含非法路径：$name")
        }
    }

    private fun copyRequired(extracted: ExtractedZip, path: String, target: File) {
        copyFile(extracted.file(path) ?: error("备份包缺少必要文件：$path"), target)
    }

    private fun copyOptional(extracted: ExtractedZip, path: String, target: File, deleteIfMissing: Boolean) {
        val source = extracted.file(path)
        if (source != null) {
            copyFile(source, target)
        } else if (deleteIfMissing) {
            target.delete()
        }
    }

    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun writeText(target: File, text: String) {
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    private fun applySharedPrefs(name: String, text: String) {
        val root = JSONObject(text)
        val entries = root.optJSONObject("entries") ?: root
        val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = entries.opt(key)) {
                null, JSONObject.NULL -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is JSONArray -> editor.putStringSet(key, jsonArrayToStringSet(value))
                else -> editor.putString(key, value.toString())
            }
        }
        if (!editor.commit()) error("无法写入偏好：$name")
    }

    private fun jsonArrayToStringSet(arr: JSONArray): Set<String> =
        buildSet {
            for (i in 0 until arr.length()) add(arr.optString(i))
        }

    private fun currentRemotePasswordsById(): Map<String, String> =
        runCatching { store.readRemotesList().associate { it.id to it.password } }
            .getOrDefault(emptyMap())

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

    private fun Any?.toJsonValue(): Any = when (this) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it ?: JSONObject.NULL) } }
        else -> this
    }

    private fun rollbackFiles(): Map<String, File> = linkedMapOf(
        "files/library.json" to store.libraryFile,
        "files/roots.list" to store.rootsListFile,
        "files/remotes.json" to store.remotesFile,
        "files/scrape-record.tsv" to store.scrapeRecordFile,
        "files/library-diagnostics.json" to diagnosticsFile(),
        "files/scrape-config.json" to scrapeConfigFile(),
    )

    private fun rollbackDir(): File =
        File(app.filesDir, "mediavault/import-backups")

    private fun cleanupOldRollbackSnapshots() {
        val files = rollbackDir().listFiles()?.filter { it.isFile && it.name.endsWith(".zip") } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_ROLLBACK_SNAPSHOTS)
            .forEach { it.delete() }
    }

    private fun diagnosticsFile(): File =
        File(app.filesDir, "mediavault/library-diagnostics.json")

    private fun scrapeConfigFile(): File =
        File(app.filesDir, "mediavault/scrape-config.json")

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun formatFileTime(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))

    private fun readRollbackCreatedAt(file: File): String? = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (entry != null) {
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n <= 0) break
                        bytes.write(buffer, 0, n)
                    }
                    return@runCatching JSONObject(bytes.toString(Charsets.UTF_8.name()))
                        .optString("createdAt", "")
                        .takeIf { it.isNotBlank() }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    private data class ExtractedZip(val dir: File, val files: Map<String, File>) {
        fun file(path: String): File? = files[path]
        fun text(path: String): String? = file(path)?.readText(Charsets.UTF_8)
        fun textRequired(path: String): String = text(path) ?: error("备份包缺少必要文件：$path")
        fun delete() {
            dir.deleteRecursively()
        }
    }

    private data class PreparedRemotes(val text: String, val restoredPasswords: Int)

    private data class PreparedScrapeSettings(val text: String, val restoredTmdbKey: Boolean)

    private fun ZipOutputStream.putFile(path: String, file: File) {
        if (!file.isFile) return
        putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 192L * 1024L * 1024L
        private const val MAX_ROLLBACK_SNAPSHOTS = 3
        private val PREF_NAMES = listOf(
            "mediavault_playback_progress",
            "mediavault_history",
            "subtitle_prefs",
            "playback_ui",
        )
        private val IMPORT_ALLOWED_ENTRIES = setOf(
            "manifest.json",
            "data/library.json",
            "data/roots.list",
            "data/remotes.redacted.json",
            "data/scrape-record.tsv",
            "data/library-diagnostics.json",
            "data/playback-progress.json",
            "data/history.json",
            "config/scrape-settings.redacted.json",
            "config/subtitle-prefs.json",
            "config/playback-ui.json",
        )
        private val ROLLBACK_ALLOWED_ENTRIES = setOf(
            "manifest.json",
            "files/library.json",
            "files/roots.list",
            "files/remotes.json",
            "files/scrape-record.tsv",
            "files/library-diagnostics.json",
            "files/scrape-config.json",
            "prefs/mediavault_playback_progress.json",
            "prefs/mediavault_history.json",
            "prefs/subtitle_prefs.json",
            "prefs/playback_ui.json",
        )
    }
}
