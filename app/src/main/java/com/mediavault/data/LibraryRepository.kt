package com.mediavault.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryRepository(context: Context) {
    val store = MediaStore(context)
    private val diagnosticsStore = LibraryDiagnosticsStore(context)
    private val app = context.applicationContext

    private val _library = MutableStateFlow(MediaLibrary(false, emptyList(), ""))
    val library: StateFlow<MediaLibrary> = _library.asStateFlow()

    private val _updatedAt = MutableStateFlow("--")
    val updatedAt: StateFlow<String> = _updatedAt.asStateFlow()

    private val _diagnostics = MutableStateFlow(diagnosticsStore.readSnapshot() ?: LibraryDiagnosticsSnapshot.EMPTY)
    val diagnostics: StateFlow<LibraryDiagnosticsSnapshot> = _diagnostics.asStateFlow()

    fun reload(): Result<Int> = runCatching {
        val text = store.readLibraryText() ?: run {
            _library.value = MediaLibrary(false, emptyList(), store.libraryFile.absolutePath)
            _updatedAt.value = "--"
            return@runCatching 0
        }
        val lib = MediaLibrary.parse(text, store.libraryFile.absolutePath)
        _library.value = lib
        _updatedAt.value = readUpdatedFromJson(text) ?: formatFileTime(store.libraryFile)
        refreshDiagnostics(lib.items)
        lib.items.size
    }

    fun importFromUri(uri: android.net.Uri): Result<Int> = store.writeLibraryFromImport(uri).onSuccess { reload() }

    fun writeItems(items: List<MediaItem>): Result<Unit> =
        store.writeLibraryJson(items).onSuccess { reload() }

    /** 刮削每批次：合并 content:// 条目并写盘，返回当前库条数 */
    fun appendContentBatch(batch: List<MediaItem>): Result<Int> = runCatching {
        if (batch.isEmpty()) return@runCatching _library.value.items.size
        mergeAndPersist(batch)
    }

    /** 单条刮削完成即落库（多线程刮削用） */
    fun appendSingleContentItem(item: MediaItem): Result<Int> = runCatching {
        mergeAndPersist(listOf(item))
    }

    /** 刮削批量写盘，不触发 reload（结束时再 reload 一次）。 */
    fun mergeContentBatchWithoutReload(batch: List<MediaItem>): Result<Int> = runCatching {
        if (batch.isEmpty()) return@runCatching _library.value.items.size
        synchronized(this) {
            val existing = _library.value.items
            val byPath = existing.associateBy { it.path }.toMutableMap()
            for (item in batch) {
                byPath[item.path] = item
            }
            val merged = byPath.values.toList()
            store.writeLibraryJson(merged).getOrThrow()
            _library.value = MediaLibrary(true, merged, store.libraryFile.absolutePath)
            refreshDiagnostics(merged)
            merged.size
        }
    }

    fun refreshDiagnostics(items: List<MediaItem> = _library.value.items): LibraryDiagnosticsSnapshot {
        val snapshot = diagnosticsStore.scanAndPersist(store, items)
        _diagnostics.value = snapshot
        return snapshot
    }

    private fun mergeAndPersist(batch: List<MediaItem>): Int {
        val existing = _library.value.items
        val byPath = existing.associateBy { it.path }.toMutableMap()
        for (item in batch) {
            byPath[item.path] = item
        }
        val merged = byPath.values.toList()
        store.writeLibraryJson(merged).getOrThrow()
        reload()
        return merged.size
    }

    /** 全部重扫前：去掉库里所有通过「文档选择器」添加的本机条目 */
    fun stripContentItems(): Result<Int> = runCatching {
        val kept = _library.value.items.filter { !LocalScanner.isContentLibraryPath(it.path) }
        store.writeLibraryJson(kept).getOrThrow()
        reload()
        kept.size
    }

    /** 全部重新刮削前：去掉库中所有远程条目 */
    fun stripRemoteItems(): Result<Int> = runCatching {
        val kept = _library.value.items.filter { !com.mediavault.remote.RemotePath.isRemote(it.path) }
        store.writeLibraryJson(kept).getOrThrow()
        reload()
        kept.size
    }

    fun removeItemsUnderRemote(remoteId: String): Result<Int> = runCatching {
        val n = store.removeLibraryItemsUnderRemote(remoteId)
        reload()
        n
    }

    fun removeItemByPath(path: String): Result<Boolean> = runCatching {
        val existing = _library.value.items
        val kept = existing.filter { it.path != path }
        if (kept.size == existing.size) return@runCatching false
        store.writeLibraryJson(kept).getOrThrow()
        reload()
        true
    }

    fun dataSizes(): DataSizes {
        val lib = store.libraryFile
        val covers = File(app.filesDir, "mediavault/covers")
        val scrape = store.scrapeRecordFile
        val videoCount = _library.value.items.size
        val (remoteFiles, remoteBytes) = com.mediavault.remote.RemoteStreamCache.cacheStats(app)
        return DataSizes(
            libraryBytes = if (lib.isFile) lib.length() else 0L,
            coverBytes = dirSize(covers),
            coverCount = covers.listFiles()?.count { it.isFile } ?: 0,
            scrapeRecordBytes = if (scrape.isFile) scrape.length() else 0L,
            videoCount = videoCount,
            remoteStreamFiles = remoteFiles,
            remoteStreamBytes = remoteBytes,
        )
    }

    fun clearCovers(): Int {
        val dir = File(app.filesDir, "mediavault/covers")
        var n = 0
        dir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        return n
    }

    /** 从库里移除某个本机根下的条目（不删根目录配置） */
    fun removeItemsUnderRoot(rootUri: String): Result<Int> = runCatching {
        val n = store.removeLibraryItemsUnderRoot(rootUri)
        reload()
        n
    }

    fun clearScrapeRecord(): Boolean = store.clearAllScrapeRecords().let { true }

    fun clearLibraryJson(): Result<Unit> = runCatching {
        store.writeLibraryJson(emptyList()).getOrThrow()
        reload()
    }

    fun clearRemoteStreamCache(): Int = com.mediavault.remote.RemoteStreamCache.clearAll(app)

    private fun readUpdatedFromJson(text: String): String? = runCatching {
        JSONObject(text).optString("updated", "").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun formatFileTime(f: File): String =
        if (!f.isFile) "--"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else dirSize(it) } ?: 0L
    }
}

data class DataSizes(
    val libraryBytes: Long,
    val coverBytes: Long,
    val coverCount: Int,
    val scrapeRecordBytes: Long,
    val videoCount: Int,
    val remoteStreamFiles: Int = 0,
    val remoteStreamBytes: Long = 0L,
)
