package com.mediavault.scrape

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mediavault.R
import com.mediavault.data.LibraryRepository
import com.mediavault.data.LibraryTaskStore
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
        taskTitle: String? = null,
        taskDetail: String = "",
        taskIssueKind: String? = null,
    ) {
        if (isRunning()) return
        val (localScopeCount, remoteScopeCount) = taskScopeCounts(localRootUris, remoteIds)
        val taskId = LibraryTaskStore(app).recordStarted(
            type = LibraryTaskStore.TYPE_SCRAPE,
            title = taskTitle ?: scrapeTaskTitle(rebuild, localRootUris, remoteIds),
            summary = app.getString(R.string.task_started_scope_fmt, localScopeCount, remoteScopeCount),
            detail = taskDetail,
            issueKind = taskIssueKind,
            localScopeCount = localScopeCount,
            remoteScopeCount = remoteScopeCount,
        )
        writeJob(JSONObject().apply {
            put("running", true)
            put("rebuild", rebuild)
            put("interrupted", false)
            put("startedAt", now())
            put("taskId", taskId)
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
        queueDone: Int = 0,
        queueTotal: Int = 0,
        scopeLabel: String = "",
    ) {
        if (ScrapeProgressThrottle.shouldEmitUi(batchCount, lastUiEmitAtMs, force = forceJobWrite)) {
            val qTotal = if (queueTotal > 0) queueTotal else _state.value.queueTotal
            val qDone = if (queueTotal > 0) queueDone else _state.value.queueDone
            val scope = scopeLabel.ifBlank { _state.value.scopeLabel }
            _state.value = _state.value.copy(
                phase = ScrapePhase.RUNNING,
                message = message,
                batchCount = batchCount,
                totalInLibrary = totalInLibrary,
                currentFileLabel = currentFileLabel.ifBlank { _state.value.currentFileLabel },
                lastBatchAt = now(),
                canResume = false,
                queueTotal = qTotal,
                queueDone = qDone,
                scopeLabel = scope,
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
        val taskId = currentTaskId()
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
        LibraryTaskStore(app).finish(
            id = taskId,
            status = LibraryTaskStore.STATUS_SUCCESS,
            summary = app.getString(R.string.task_scrape_done_fmt, scannedThisRun, totalInLibrary),
            detail = app.getString(R.string.task_scrape_done_detail_fmt, tmdbHits, tmdbMisses, coverAdded),
            issueKind = if (weakCount > 0) "low_confidence_match" else null,
        )
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
        val taskId = currentTaskId()
        markInterrupted()
        LibraryTaskStore(app).finish(
            id = taskId,
            status = LibraryTaskStore.STATUS_FAILED,
            summary = app.getString(R.string.task_failed_fmt, msg),
        )
        _state.value = _state.value.copy(
            phase = ScrapePhase.ERROR,
            message = msg,
            canResume = true,
        )
    }

    internal fun onCancelled(partial: Int, totalInLibrary: Int) {
        val taskId = currentTaskId()
        markInterrupted()
        LibraryTaskStore(app).finish(
            id = taskId,
            status = LibraryTaskStore.STATUS_CANCELLED,
            summary = app.getString(R.string.task_scrape_cancelled_fmt, partial, totalInLibrary),
        )
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

    private fun currentTaskId(): String =
        readJob()?.optString("taskId", "").orEmpty()

    private fun writeJob(o: JSONObject) {
        jobFile.parentFile?.mkdirs()
        jobFile.writeText(o.toString(2))
    }

    private fun taskScopeCounts(localRootUris: List<String>?, remoteIds: List<String>?): Pair<Int, Int> {
        val fullScope = localRootUris.isNullOrEmpty() && remoteIds.isNullOrEmpty()
        val localCount = if (fullScope) repository.store.readLocalRootUris().size else localRootUris.orEmpty().size
        val remoteCount = if (fullScope) repository.store.readRemotesList().size else remoteIds.orEmpty().size
        return localCount to remoteCount
    }

    private fun scrapeTaskTitle(
        rebuild: Boolean,
        localRootUris: List<String>?,
        remoteIds: List<String>?,
    ): String {
        val scoped = !localRootUris.isNullOrEmpty() || !remoteIds.isNullOrEmpty()
        return app.getString(
            when {
                scoped && rebuild -> R.string.task_title_scrape_scope_rebuild
                scoped -> R.string.task_title_scrape_scope
                rebuild -> R.string.task_title_scrape_rebuild
                else -> R.string.task_title_scrape_incremental
            },
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
