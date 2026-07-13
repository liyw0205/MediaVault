package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.LibraryDiagnosticsSnapshot
import com.mediavault.data.LibraryIssue
import com.mediavault.data.LibraryRepository
import com.mediavault.data.LibraryTaskStore
import com.mediavault.data.MediaItem
import com.mediavault.data.RemoteCapability
import com.mediavault.data.RemoteSourceActionPolicy
import com.mediavault.data.SourceHealth
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RemoteSourceManagerDialog {
    fun show(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        snapshot: LibraryDiagnosticsSnapshot,
        onDiagnosticsChanged: () -> Unit = {},
        onSourceIssuesRequested: (String) -> Unit,
        onCredentialRepairRequested: (List<String>) -> Boolean,
    ) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_remote_source_manager, null, false)
        val summary = root.findViewById<TextView>(R.id.remoteSourceManagerSummary)
        val recheckBad = root.findViewById<MaterialButton>(R.id.remoteSourceRecheckBad)
        val recycler = root.findViewById<RecyclerView>(R.id.remoteSourceManagerRecycler)
        var dialog: AlertDialog? = null
        var currentSources = emptyList<RemoteSourceStatus>()
        recycler.layoutManager = LinearLayoutManager(activity)
        lateinit var adapter: SourceAdapter
        adapter = SourceAdapter(
            activity = activity,
            onOpenIssues = { source ->
                if (source.issueCount <= 0) {
                    Toast.makeText(activity, R.string.remote_source_no_issues, Toast.LENGTH_SHORT).show()
                } else {
                    dialog?.dismiss()
                    onSourceIssuesRequested(source.config.id)
                }
            },
            onRepairCredential = { source ->
                if (onCredentialRepairRequested(listOf(source.config.id))) {
                    dialog?.dismiss()
                }
            },
            onOpenMedia = { source ->
                showSourceMedia(activity, source, onSourceIssuesRequested)
            },
            onRecheck = { source ->
                recheckRemoteSources(activity, repository, listOf(source.config.id)) { nextSnapshot ->
                    currentSources = render(activity, repository, summary, recheckBad, adapter, nextSnapshot)
                    onDiagnosticsChanged()
                }
            },
            onRescrape = { source ->
                if (rescrapeSource(activity, repository, source)) {
                    dialog?.dismiss()
                }
            },
        )
        recycler.adapter = adapter
        currentSources = render(activity, repository, summary, recheckBad, adapter, snapshot)
        recheckBad.setOnClickListener {
            val ids = currentSources
                .filter { it.shouldRecheck }
                .map { it.config.id }
            if (ids.isEmpty()) {
                Toast.makeText(activity, R.string.remote_source_recheck_bad_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            recheckRemoteSources(activity, repository, ids) { nextSnapshot ->
                currentSources = render(activity, repository, summary, recheckBad, adapter, nextSnapshot)
                onDiagnosticsChanged()
            }
        }
        dialog = MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.remote_source_manager_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = root,
        )
    }

    private fun render(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        summary: TextView,
        recheckBad: MaterialButton,
        adapter: SourceAdapter,
        snapshot: LibraryDiagnosticsSnapshot,
    ): List<RemoteSourceStatus> {
        val sources = buildSources(repository, snapshot)
        summary.text = activity.getString(
            R.string.remote_source_manager_summary_fmt,
            sources.size,
            sources.sumOf { it.mediaCount },
            sources.sumOf { it.issueCount },
            sources.count { it.isBad },
            sources.count { it.isUnchecked },
        )
        recheckBad.isEnabled = sources.any { it.shouldRecheck }
        adapter.submit(sources)
        return sources
    }

    private fun buildSources(
        repository: LibraryRepository,
        snapshot: LibraryDiagnosticsSnapshot,
    ): List<RemoteSourceStatus> {
        val items = repository.library.value.items
        val mediaCounts = items
            .mapNotNull { RemotePath.parse(it.path)?.configId }
            .groupingBy { it }
            .eachCount()
        val issuesByRemote = snapshot.issues.groupBy { RemotePath.parse(it.path)?.configId.orEmpty() }
        val issuesByPath = snapshot.issues.groupBy { it.path }
        val healthById = snapshot.sourceHealth.associateBy { it.sourceId }
        val capabilitiesById = snapshot.remoteCapabilities.groupBy { it.sourceId }
        return repository.store.readRemotesList()
            .sortedWith(compareByDescending<RemoteConfig> { (issuesByRemote[it.id]?.size ?: 0) > 0 }
                .thenBy { it.name.ifBlank { it.id } })
            .map { cfg ->
                val health = healthById[cfg.id]
                val capabilities = capabilitiesById[cfg.id].orEmpty()
                val latestCapabilities = capabilities
                    .groupBy { it.key }
                    .mapNotNull { (_, caps) -> caps.maxByOrNull { it.lastCheckedAt } }
                val cacheBytes = latestCapabilities.maxOfOrNull { it.cacheTotalBytes }
                    ?: latestCapabilities.sumOf { it.cachePrefixBytes + it.cacheRangeBytes }
                RemoteSourceStatus(
                    config = cfg,
                    mediaCount = mediaCounts[cfg.id] ?: 0,
                    issues = issuesByRemote[cfg.id].orEmpty(),
                    mediaItems = items
                        .filter { RemotePath.parse(it.path)?.configId == cfg.id }
                        .sortedBy { it.displayTitle() },
                    issuesByPath = issuesByPath,
                    health = health,
                    latestCapability = latestCapabilities.maxByOrNull { it.lastCheckedAt },
                    cacheBytes = cacheBytes,
                )
            }
    }

    private fun rescrapeSource(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        source: RemoteSourceStatus,
    ): Boolean {
        val app = activity.application as MediaVaultApp
        if (source.config.credentialMissing) {
            Toast.makeText(activity, R.string.library_issue_missing_remote_credential_action, Toast.LENGTH_LONG).show()
            return false
        }
        if (app.scrapeManager.isRunning()) {
            Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
            return false
        }
        repository.store.clearScrapeRecordsUnderRemote(source.config.id)
        app.scrapeManager.start(
            rebuild = false,
            remoteIds = listOf(source.config.id),
            taskTitle = activity.getString(R.string.task_title_source_rescrape),
            taskDetail = source.name,
            taskIssueKind = source.topIssueKind,
        )
        Toast.makeText(activity, activity.getString(R.string.remote_source_rescrape_started_fmt, source.name), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun recheckRemoteSources(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        remoteIds: List<String>,
        onDone: (LibraryDiagnosticsSnapshot) -> Unit,
    ) {
        val ids = remoteIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        val taskStore = LibraryTaskStore(activity)
        val runningText = activity.getString(R.string.remote_source_recheck_running_fmt, ids.size)
        val taskId = taskStore.recordStarted(
            type = LibraryTaskStore.TYPE_SOURCE_RECHECK,
            title = activity.getString(R.string.task_title_source_recheck_scope),
            summary = runningText,
            remoteScopeCount = ids.size,
        )
        Toast.makeText(activity, runningText, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.refreshDiagnosticsForRemoteIds(ids) }
            }
            result
                .onSuccess { snapshot ->
                    val health = snapshot.sourceHealth.filter { it.sourceId in ids }
                    val ok = health.count { it.reachable == true }
                    val bad = health.count { it.reachable == false }
                    val unchecked = health.count { it.reachable == null }
                    val summary = activity.getString(
                        R.string.remote_source_recheck_done_fmt,
                        ids.size,
                        ok,
                        bad,
                        unchecked,
                    )
                    taskStore.finish(
                        id = taskId,
                        status = if (bad > 0 || unchecked > 0) LibraryTaskStore.STATUS_PARTIAL else LibraryTaskStore.STATUS_SUCCESS,
                        summary = summary,
                        issueKind = when {
                            health.any { it.lastErrorKind == "credential_missing" } -> "missing_remote_credential"
                            bad > 0 -> "stale_remote"
                            else -> null
                        },
                    )
                    onDone(snapshot)
                    Toast.makeText(activity, summary, Toast.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    val msg = e.message ?: activity.getString(R.string.action_failed)
                    taskStore.finish(
                        id = taskId,
                        status = LibraryTaskStore.STATUS_FAILED,
                        summary = activity.getString(R.string.task_failed_fmt, msg),
                    )
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showSourceMedia(
        activity: AppCompatActivity,
        source: RemoteSourceStatus,
        onSourceIssuesRequested: (String) -> Unit,
    ) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_remote_source_media, null, false)
        val summary = root.findViewById<TextView>(R.id.remoteSourceMediaSummary)
        val recycler = root.findViewById<RecyclerView>(R.id.remoteSourceMediaRecycler)
        val issueItemCount = source.mediaItems.count { source.issuesByPath[it.path].orEmpty().isNotEmpty() }
        summary.text = activity.getString(
            R.string.remote_source_media_summary_fmt,
            source.name,
            source.mediaItems.size,
            issueItemCount,
            source.issueCount,
        )
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = MediaAdapter(
            activity = activity,
            source = source,
            onOpenSourceIssues = onSourceIssuesRequested,
        )
        MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.remote_source_media_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = root,
        )
    }

    private class SourceAdapter(
        private val activity: AppCompatActivity,
        private val onOpenIssues: (RemoteSourceStatus) -> Unit,
        private val onRepairCredential: (RemoteSourceStatus) -> Unit,
        private val onOpenMedia: (RemoteSourceStatus) -> Unit,
        private val onRecheck: (RemoteSourceStatus) -> Unit,
        private val onRescrape: (RemoteSourceStatus) -> Unit,
    ) : RecyclerView.Adapter<SourceVH>() {
        private var sources: List<RemoteSourceStatus> = emptyList()

        fun submit(next: List<RemoteSourceStatus>) {
            sources = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = sources.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_source_status, parent, false)
            return SourceVH(view)
        }

        override fun onBindViewHolder(holder: SourceVH, position: Int) {
            val source = sources[position]
            val actions = RemoteSourceActionPolicy.evaluate(
                credentialMissing = source.config.credentialMissing,
                mediaCount = source.mediaCount,
                issueCount = source.issueCount,
                reachable = source.health?.reachable,
            )
            holder.name.text = source.name
            holder.meta.text = activity.getString(
                R.string.remote_source_status_meta_fmt,
                source.config.type.uppercase(),
                source.config.host,
                source.config.port,
                source.config.basePath,
            )
            holder.counts.text = activity.getString(
                R.string.remote_source_status_counts_fmt,
                source.mediaCount,
                source.issueCount,
                LibraryUi.formatBytes(source.cacheBytes),
            )
            holder.health.text = activity.getString(
                R.string.remote_source_status_health_fmt,
                healthLabel(source),
                source.lastCheckedAt,
                rangeLabel(source),
            )
            holder.health.setTextColor(activity.getColor(healthColor(source)))
            holder.error.apply {
                val error = source.errorMessage
                visibility = if (error.isBlank()) View.GONE else View.VISIBLE
                text = activity.getString(R.string.remote_source_status_error_fmt, error)
            }
            holder.openIssues.isEnabled = actions.canOpenIssues
            holder.openIssues.setOnClickListener { onOpenIssues(source) }
            holder.openMedia.isEnabled = actions.canOpenMedia
            holder.openMedia.setOnClickListener { onOpenMedia(source) }
            holder.recheck.isEnabled = actions.canRecheck
            holder.recheck.setOnClickListener { onRecheck(source) }
            holder.rescrape.isEnabled = actions.canRescrape
            holder.rescrape.setOnClickListener {
                if (actions.canRescrape) {
                    onRescrape(source)
                } else {
                    val message = when (actions.rescrapeBlockReason) {
                        RemoteSourceActionPolicy.RescrapeBlockReason.CREDENTIAL_MISSING ->
                            R.string.remote_source_rescrape_blocked_credential
                        RemoteSourceActionPolicy.RescrapeBlockReason.NO_MEDIA ->
                            R.string.remote_source_rescrape_blocked_no_media
                        null -> R.string.action_failed
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
            holder.credential.visibility = if (source.config.credentialMissing) View.VISIBLE else View.GONE
            holder.credential.setOnClickListener { onRepairCredential(source) }
        }

        private fun healthLabel(source: RemoteSourceStatus): String =
            when {
                source.config.credentialMissing -> activity.getString(R.string.remote_source_health_missing_credential)
                source.health?.reachable == true -> activity.getString(R.string.remote_source_health_ok)
                source.health?.reachable == false -> activity.getString(R.string.remote_source_health_bad)
                else -> activity.getString(R.string.remote_source_health_unchecked)
            }

        private fun healthColor(source: RemoteSourceStatus): Int =
            when {
                source.config.credentialMissing -> R.color.mv_amber
                source.health?.reachable == true -> R.color.mv_primary
                source.health?.reachable == false -> R.color.mv_danger
                else -> R.color.mv_text_secondary
            }

        private fun rangeLabel(source: RemoteSourceStatus): String {
            val range = source.latestCapability?.supportsRange ?: source.health?.supportsRange
            return when (range) {
                true -> activity.getString(R.string.remote_source_range_yes)
                false -> activity.getString(R.string.remote_source_range_no)
                null -> activity.getString(R.string.remote_source_range_unknown)
            }
        }
    }

    private class SourceVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.remoteSourceStatusName)
        val meta: TextView = view.findViewById(R.id.remoteSourceStatusMeta)
        val counts: TextView = view.findViewById(R.id.remoteSourceStatusCounts)
        val health: TextView = view.findViewById(R.id.remoteSourceStatusHealth)
        val error: TextView = view.findViewById(R.id.remoteSourceStatusError)
        val openIssues: MaterialButton = view.findViewById(R.id.remoteSourceStatusOpenIssues)
        val openMedia: MaterialButton = view.findViewById(R.id.remoteSourceStatusOpenMedia)
        val recheck: MaterialButton = view.findViewById(R.id.remoteSourceStatusRecheck)
        val rescrape: MaterialButton = view.findViewById(R.id.remoteSourceStatusRescrape)
        val credential: MaterialButton = view.findViewById(R.id.remoteSourceStatusCredential)
    }

    private class MediaAdapter(
        private val activity: AppCompatActivity,
        private val source: RemoteSourceStatus,
        private val onOpenSourceIssues: (String) -> Unit,
    ) : RecyclerView.Adapter<MediaVH>() {
        override fun getItemCount(): Int = source.mediaItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_source_media, parent, false)
            return MediaVH(view)
        }

        override fun onBindViewHolder(holder: MediaVH, position: Int) {
            val item = source.mediaItems[position]
            val issues = source.issuesByPath[item.path].orEmpty()
            holder.title.text = item.displayTitle()
            holder.meta.text = mediaMeta(item)
            holder.issues.apply {
                visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
                text = activity.getString(
                    R.string.remote_source_media_issues_fmt,
                    issues.size,
                    issues.map { LibraryMaintenanceDialog.issueLabel(activity, it.kind) }
                        .distinct()
                        .take(4)
                        .joinToString("、"),
                )
            }
            holder.detail.setOnClickListener {
                activity.startActivity(VideoDetailActivity.intent(activity, item.path))
            }
            holder.openSourceIssues.apply {
                visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
                setOnClickListener { onOpenSourceIssues(source.config.id) }
            }
        }

        private fun mediaMeta(item: MediaItem): String {
            val parsed = RemotePath.parse(item.path)
            val rel = parsed?.relativePath.orEmpty().ifBlank { item.path }
            val row = LibraryUi.rowSubtitle(item)
            return listOf(row, rel)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }

    private class MediaVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.remoteSourceMediaTitle)
        val meta: TextView = view.findViewById(R.id.remoteSourceMediaMeta)
        val issues: TextView = view.findViewById(R.id.remoteSourceMediaIssues)
        val detail: MaterialButton = view.findViewById(R.id.remoteSourceMediaDetail)
        val openSourceIssues: MaterialButton = view.findViewById(R.id.remoteSourceMediaOpenSourceIssues)
    }

    private data class RemoteSourceStatus(
        val config: RemoteConfig,
        val mediaCount: Int,
        val issues: List<LibraryIssue>,
        val mediaItems: List<MediaItem>,
        val issuesByPath: Map<String, List<LibraryIssue>>,
        val health: SourceHealth?,
        val latestCapability: RemoteCapability?,
        val cacheBytes: Long,
    ) {
        val name: String = config.name.ifBlank { config.id }
        val issueCount: Int = issues.size
        val topIssueKind: String? = issues.groupingBy { it.kind }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .firstOrNull()
            ?.key
        val isBad: Boolean = config.credentialMissing || health?.reachable == false
        val isUnchecked: Boolean = !config.credentialMissing && (health == null || health.reachable == null)
        val shouldRecheck: Boolean = isBad || isUnchecked
        val lastCheckedAt: String = health?.lastCheckedAt?.takeIf { it.isNotBlank() }
            ?: latestCapability?.lastCheckedAt?.takeIf { it.isNotBlank() }
            ?: "--"
        val errorMessage: String = if (config.credentialMissing) {
            ""
        } else {
            health?.lastErrorMessage?.takeIf { it.isNotBlank() }
                ?: latestCapability?.lastErrorMessage?.takeIf { it.isNotBlank() }
                ?: ""
        }
    }
}
