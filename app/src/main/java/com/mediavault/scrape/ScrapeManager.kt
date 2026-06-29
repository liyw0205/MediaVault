package com.mediavault.scrape

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mediavault.data.LibraryRepository
import com.mediavault.data.TmdbMatchHeuristics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScrapeManager(
    private val app: Context,
    val repository: LibraryRepository,
) {
    private val jobFile: File
        get() = File(app.filesDir, "mediavault/scrape-job.json")

    private val _state = MutableStateFlow(ScrapeUiState())
    val state: StateFlow<ScrapeUiState> = _state.asStateFlow()
    private var lastJobPersistedBatch = 0
    private var lastUiEmitAtMs = 0L
    @Volatile var tmdbHits: Int = 0
    @Volatile var tmdbMisses: Int = 0
    @Volatile var coverAdded: Int = 0

    fun restoreJobHint() {
        val hint = readJob()
        val canResume = hint?.optBoolean("interrupted", false) == true ||
            (hint?.optBoolean("running", false) == true)
        if (canResume) {
            _state.value = _state.value.copy(
                canResume = true,
                message = "上次刮削可能未完成，可点「全部刮削」从记录续扫",
            )
        }
    }

    fun isRunning(): Boolean = _state.value.phase == ScrapePhase.RUNNING

    fun start(
        rebuild: Boolean,
        localRootUris: List<String>? = null,
        remoteIds: List<String>? = null,
    ) {
        if (isRunning()) return
        writeJob(JSONObject().apply {
            put("running", true)
            put("rebuild", rebuild)
            put("interrupted", false)
            put("startedAt", now())
            if (!localRootUris.isNullOrEmpty()) {
                put("rootUris", JSONArray(localRootUris))
            }
            if (!remoteIds.isNullOrEmpty()) {
                put("remoteIds", JSONArray(remoteIds))
            }
        })
        val scopeHint = when {
            !localRootUris.isNullOrEmpty() && !remoteIds.isNullOrEmpty() -> "所选本地与远程…"
            !remoteIds.isNullOrEmpty() -> "所选远程…"
            !localRootUris.isNullOrEmpty() -> "所选目录…"
            else -> null
        }
        _state.value = ScrapeUiState(
            phase = ScrapePhase.RUNNING,
            rebuild = rebuild,
            message = scopeHint?.let { "正在刮削$it" } ?: "正在启动后台刮削…",
            totalInLibrary = repository.library.value.items.size,
        )
        lastJobPersistedBatch = 0
        lastUiEmitAtMs = 0L
        tmdbHits = 0
        tmdbMisses = 0
        coverAdded = 0
        val intent = Intent(app, ScrapeForegroundService::class.java).apply {
            putExtra(ScrapeForegroundService.EXTRA_REBUILD, rebuild)
            if (!localRootUris.isNullOrEmpty()) {
                putStringArrayListExtra(ScrapeForegroundService.EXTRA_ROOT_URIS, ArrayList(localRootUris))
            }
            if (!remoteIds.isNullOrEmpty()) {
                putStringArrayListExtra(ScrapeForegroundService.EXTRA_REMOTE_IDS, ArrayList(remoteIds))
            }
        }
        ContextCompat.startForegroundService(app, intent)
    }

    fun cancel() {
        app.stopService(Intent(app, ScrapeForegroundService::class.java))
    }

    internal fun onProgress(
        message: String,
        batchCount: Int,
        totalInLibrary: Int,
        currentFileLabel: String = "",
        forceJobWrite: Boolean = false,
    ) {
        if (ScrapeProgressThrottle.shouldEmitUi(batchCount, lastUiEmitAtMs, force = forceJobWrite)) {
            _state.value = _state.value.copy(
                phase = ScrapePhase.RUNNING,
                message = message,
                batchCount = batchCount,
                totalInLibrary = totalInLibrary,
                currentFileLabel = currentFileLabel.ifBlank { _state.value.currentFileLabel },
                lastBatchAt = now(),
                canResume = false,
            )
            lastUiEmitAtMs = System.currentTimeMillis()
        }
        if (!forceJobWrite && !ScrapeProgressThrottle.shouldPersistJob(batchCount, lastJobPersistedBatch)) {
            return
        }
        lastJobPersistedBatch = batchCount
        val job = readJob() ?: JSONObject()
        job.put("running", true)
        job.put("batchCount", batchCount)
        job.put("lastMessage", message)
        writeJob(job)
    }

    internal fun onFinished(totalInLibrary: Int, scannedThisRun: Int) {
        writeJob(JSONObject().apply {
            put("running", false)
            put("interrupted", false)
            put("finishedAt", now())
            put("scannedThisRun", scannedThisRun)
            put("tmdbHits", tmdbHits)
            put("tmdbMisses", tmdbMisses)
            put("coverAdded", coverAdded)
        })
        val reportSuffix = buildString {
            if (tmdbHits + tmdbMisses > 0) {
                append("，TMDB 命中 ")
                append(tmdbHits)
                append("、未命中 ")
                append(tmdbMisses)
            }
            if (coverAdded > 0) {
                append("，新封面 ")
                append(coverAdded)
            }
        }
        val weakCount = TmdbMatchHeuristics.weakTmdbItems(repository.library.value.items).size
        _state.value = ScrapeUiState(
            phase = ScrapePhase.DONE,
            message = "完成：本轮 $scannedThisRun 条，库中共 $totalInLibrary 条$reportSuffix",
            totalInLibrary = totalInLibrary,
            batchCount = scannedThisRun,
            lastBatchAt = now(),
            weakTmdbCount = weakCount,
        )
    }

    internal fun onError(msg: String) {
        markInterrupted()
        _state.value = _state.value.copy(
            phase = ScrapePhase.ERROR,
            message = msg,
            canResume = true,
        )
    }

    internal fun onCancelled(partial: Int, totalInLibrary: Int) {
        markInterrupted()
        _state.value = ScrapeUiState(
            phase = ScrapePhase.CANCELLED,
            message = "已停止（已写入 $partial 条，库中共 $totalInLibrary 条）",
            batchCount = partial,
            totalInLibrary = totalInLibrary,
            canResume = true,
        )
    }

    private fun markInterrupted() {
        val job = readJob() ?: JSONObject()
        job.put("running", false)
        job.put("interrupted", true)
        writeJob(job)
    }

    private fun readJob(): JSONObject? = runCatching {
        if (!jobFile.isFile) return null
        JSONObject(jobFile.readText())
    }.getOrNull()

    private fun writeJob(o: JSONObject) {
        jobFile.parentFile?.mkdirs()
        jobFile.writeText(o.toString(2))
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}