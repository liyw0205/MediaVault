package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.LibraryIssue
import com.mediavault.data.LibraryRepository
import com.mediavault.data.ScrapeEvidence
import com.mediavault.remote.RemotePath
import kotlinx.coroutines.launch

object LibraryMaintenanceDialog {
    private const val PAGE_SIZE = 50

    fun show(activity: AppCompatActivity, repository: LibraryRepository, onChanged: () -> Unit) {
        val snapshot = repository.refreshDiagnostics()
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_library_maintenance, null, false)
        val summary = root.findViewById<TextView>(R.id.libraryMaintenanceSummary)
        val filter = root.findViewById<Spinner>(R.id.libraryMaintenanceFilter)
        val recycler = root.findViewById<RecyclerView>(R.id.libraryMaintenanceRecycler)
        val prev = root.findViewById<MaterialButton>(R.id.libraryMaintenancePrev)
        val next = root.findViewById<MaterialButton>(R.id.libraryMaintenanceNext)
        val pageLabel = root.findViewById<TextView>(R.id.libraryMaintenancePage)
        recycler.layoutManager = LinearLayoutManager(activity)
        lateinit var refreshAll: () -> Unit
        val adapter = IssueAdapter(activity, repository, onChanged) {
            refreshAll()
        }
        recycler.adapter = adapter

        var allIssues = snapshot.issues
        var selectedKind: String? = null
        var pageIndex = 0
        var options: List<FilterOption> = emptyList()

        fun filteredIssues(): List<LibraryIssue> =
            selectedKind?.let { kind -> allIssues.filter { it.kind == kind } } ?: allIssues

        fun maxPage(total: Int): Int =
            if (total <= 0) 0 else (total - 1) / PAGE_SIZE

        fun renderPage() {
            val filtered = filteredIssues()
            pageIndex = pageIndex.coerceIn(0, maxPage(filtered.size))
            val from = pageIndex * PAGE_SIZE
            val page = filtered.drop(from).take(PAGE_SIZE)
            adapter.submit(page)
            prev.isEnabled = pageIndex > 0
            next.isEnabled = pageIndex < maxPage(filtered.size)
            pageLabel.text = if (filtered.isEmpty()) {
                activity.getString(R.string.library_maintenance_empty_filter)
            } else {
                activity.getString(
                    R.string.library_maintenance_page_fmt,
                    from + 1,
                    from + page.size,
                    filtered.size,
                )
            }
            renderSummary(activity, summary, repository.diagnostics.value, filtered.size)
        }

        fun rebuildFilterOptions() {
            val counts = allIssues.groupingBy { it.kind }.eachCount()
            options = buildList {
                add(FilterOption(null, activity.getString(R.string.library_maintenance_filter_all, allIssues.size)))
                counts.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
                    add(FilterOption(kind, activity.getString(R.string.library_maintenance_filter_kind, issueLabel(activity, kind), count)))
                }
            }
            val labels = options.map { it.label }
            filter.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            val selected = options.indexOfFirst { it.kind == selectedKind }.takeIf { it >= 0 } ?: 0
            filter.setSelection(selected, false)
            selectedKind = options.getOrNull(selected)?.kind
        }

        fun applySnapshot(nextSnapshot: com.mediavault.data.LibraryDiagnosticsSnapshot) {
            allIssues = nextSnapshot.issues
            if (selectedKind != null && allIssues.none { it.kind == selectedKind }) {
                selectedKind = null
                pageIndex = 0
            }
            rebuildFilterOptions()
            renderPage()
        }

        refreshAll = {
            applySnapshot(repository.refreshDiagnostics())
        }

        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedKind = options.getOrNull(position)?.kind
                pageIndex = 0
                renderPage()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        prev.setOnClickListener {
            pageIndex--
            renderPage()
        }
        next.setOnClickListener {
            pageIndex++
            renderPage()
        }
        applySnapshot(snapshot)
        MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.library_maintenance_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = root,
        )
    }

    private fun renderSummary(
        activity: AppCompatActivity,
        summary: TextView,
        snapshot: com.mediavault.data.LibraryDiagnosticsSnapshot,
        visibleCount: Int,
    ) {
        summary.text = buildString {
            append(activity.getString(
                R.string.library_maintenance_summary_fmt,
                snapshot.itemCount,
                snapshot.localCount,
                snapshot.remoteCount,
                snapshot.totalIssues,
            ))
            append('\n')
            append(activity.getString(R.string.library_maintenance_scanned_fmt, snapshot.scannedAt))
            append('\n')
            append(sourceHealthSummary(activity, snapshot.sourceHealth))
            append('\n')
            append(RemoteDiagnosticsText.capabilityText(activity, snapshot.remoteCapabilities))
            append('\n')
            append(activity.getString(R.string.library_maintenance_list_hint, visibleCount))
        }
    }

    private class IssueAdapter(
        private val activity: AppCompatActivity,
        private val repository: LibraryRepository,
        private val onChanged: () -> Unit,
        private val onRefresh: () -> Unit,
    ) : RecyclerView.Adapter<IssueVH>() {
        private var issues: List<LibraryIssue> = emptyList()

        fun submit(next: List<LibraryIssue>) {
            issues = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = issues.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_issue, parent, false)
            return IssueVH(view)
        }

        override fun onBindViewHolder(holder: IssueVH, position: Int) {
            val issue = issues[position]
            holder.title.text = issue.title.ifBlank { issue.path.substringAfterLast('/') }
            holder.meta.text = activity.getString(
                R.string.library_issue_meta_fmt,
                issueLabel(activity, issue.kind),
                issue.detail,
                issue.path,
            )
            holder.open.setOnClickListener {
                activity.startActivity(VideoDetailActivity.intent(activity, issue.path))
            }
            holder.evidence.setOnClickListener {
                showEvidence(issue)
            }
            holder.rescrape.setOnClickListener {
                rescrapeIssue(issue)
            }
            holder.remove.setOnClickListener {
                confirmRemove(issue)
            }
        }

        private fun showEvidence(issue: LibraryIssue) {
            val title = issue.title.ifBlank { issue.path.substringAfterLast('/') }
            val msg = issue.evidence?.let { evidence ->
                activity.getString(
                    R.string.library_issue_evidence_deep_fmt,
                    issueLabel(activity, issue.kind),
                    title,
                    issue.detail,
                    issue.path,
                    sourceLine(evidence),
                    evidence.fileName.ifBlank { "-" },
                    evidence.parsedTitle.ifBlank { "-" },
                    seasonLine(evidence),
                    if (evidence.nfoHit) activity.getString(R.string.library_issue_evidence_yes) else activity.getString(R.string.library_issue_evidence_no),
                    tmdbLine(evidence),
                    tmdbEvidenceLine(evidence),
                    coverLine(evidence),
                    evidence.subtitles.size.toString(),
                    evidence.sidecarFiles.take(6).joinToString(", ").ifBlank { "-" },
                )
            } ?: activity.getString(
                R.string.library_issue_evidence_fmt,
                issueLabel(activity, issue.kind),
                title,
                issue.detail,
                issue.path,
            )
            MvDialog.show(
                MvDialog.builder(activity)
                    .setTitle(R.string.library_issue_evidence)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null),
            )
        }

        private fun rescrapeIssue(issue: LibraryIssue) {
            if (rescrapeRemote(issue)) return
            if (rescrapeLocal(issue)) return
            Toast.makeText(activity, R.string.library_issue_rescrape_no_scope, Toast.LENGTH_LONG).show()
        }

        private fun rescrapeRemote(issue: LibraryIssue): Boolean {
            val parsed = RemotePath.parse(issue.path) ?: return false
            val app = activity.application as MediaVaultApp
            if (app.scrapeManager.isRunning()) {
                Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
                return true
            }
            app.repository.store.clearScrapeRecordPath(issue.path)
            app.scrapeManager.start(false, remoteIds = listOf(parsed.configId))
            Toast.makeText(activity, activity.getString(R.string.library_issue_rescrape_started, issue.title.ifBlank { issue.path }), Toast.LENGTH_SHORT).show()
            return true
        }

        private fun rescrapeLocal(issue: LibraryIssue): Boolean {
            val app = activity.application as MediaVaultApp
            val root = app.repository.store.readLocalRootUris().firstOrNull { issue.path.startsWith(it) } ?: return false
            if (app.scrapeManager.isRunning()) {
                Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
                return true
            }
            app.repository.store.clearScrapeRecordPath(issue.path)
            app.scrapeManager.start(false, localRootUris = listOf(root))
            Toast.makeText(activity, activity.getString(R.string.library_issue_rescrape_started, issue.title.ifBlank { issue.path }), Toast.LENGTH_SHORT).show()
            return true
        }

        private fun confirmRemove(issue: LibraryIssue) {
            MvDialog.show(
                MvDialog.builder(activity)
                    .setMessage(activity.getString(R.string.library_issue_remove_confirm, issue.title.ifBlank { issue.path }))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        activity.lifecycleScope.launch {
                            repository.removeItemByPath(issue.path)
                                .onSuccess { removed ->
                                    val msg = if (removed) R.string.library_issue_removed else R.string.library_issue_not_found
                                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                                    onChanged()
                                    val next = repository.diagnostics.value.issues
                                    submit(next)
                                    onRefresh()
                                }
                                .onFailure { e ->
                                    Toast.makeText(activity, e.message ?: activity.getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null),
            )
        }
    }

    private class IssueVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.libraryIssueTitle)
        val meta: TextView = view.findViewById(R.id.libraryIssueMeta)
        val open: MaterialButton = view.findViewById(R.id.libraryIssueOpen)
        val evidence: MaterialButton = view.findViewById(R.id.libraryIssueEvidence)
        val rescrape: MaterialButton = view.findViewById(R.id.libraryIssueRescrape)
        val remove: MaterialButton = view.findViewById(R.id.libraryIssueRemove)
    }

    private fun issueLabel(activity: AppCompatActivity, kind: String): String {
        val resId = when (kind) {
            "missing_path" -> R.string.library_issue_missing_path
            "duplicate_path" -> R.string.library_issue_duplicate_path
            "duplicate_title" -> R.string.library_issue_duplicate_title
            "stale_remote" -> R.string.library_issue_stale_remote
            "missing_cover" -> R.string.library_issue_missing_cover
            "unmatched" -> R.string.library_issue_unmatched
            "low_confidence_match" -> R.string.library_issue_low_confidence_match
            else -> 0
        }
        return if (resId != 0) activity.getString(resId) else kind
    }

    private fun sourceHealthSummary(
        activity: AppCompatActivity,
        sources: List<com.mediavault.data.SourceHealth>,
    ): String {
        val ok = sources.count { it.reachable == true }
        val bad = sources.count { it.reachable == false }
        val unchecked = sources.count { it.reachable == null }
        return activity.getString(R.string.source_health_summary_fmt, sources.size, ok, bad, unchecked)
    }

    private data class FilterOption(
        val kind: String?,
        val label: String,
    )

    private fun sourceLine(evidence: ScrapeEvidence): String =
        listOf(evidence.sourceType, evidence.sourcePath).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" }

    private fun seasonLine(evidence: ScrapeEvidence): String {
        val year = evidence.year.ifBlank { "?" }
        val se = when {
            evidence.season.isNotBlank() && evidence.episode.isNotBlank() -> "S${evidence.season}E${evidence.episode}"
            evidence.season.isNotBlank() -> "S${evidence.season}"
            evidence.episode.isNotBlank() -> "E${evidence.episode}"
            else -> "-"
        }
        return "$year / $se"
    }

    private fun tmdbLine(evidence: ScrapeEvidence): String {
        val id = evidence.tmdbId.ifBlank { "-" }
        val title = evidence.tmdbTitle.ifBlank { "-" }
        val year = evidence.tmdbYear.ifBlank { "?" }
        return "$id · $title ($year)"
    }

    private fun tmdbEvidenceLine(evidence: ScrapeEvidence): String =
        listOf(evidence.tmdbConfidence, evidence.tmdbReason)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "-" }

    private fun coverLine(evidence: ScrapeEvidence): String =
        listOf(evidence.coverSource, evidence.coverLocal).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" }
}
