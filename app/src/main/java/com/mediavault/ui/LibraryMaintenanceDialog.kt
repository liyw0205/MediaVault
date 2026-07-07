package com.mediavault.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
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
import com.mediavault.data.ScrapeEvidence
import com.mediavault.remote.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LibraryMaintenanceDialog {
    private const val PAGE_SIZE = 50
    private const val REPAIR_QUEUE_KIND = "__repair_queue"
    private val REPAIR_QUEUE_ISSUE_KINDS = setOf(
        "missing_remote_credential",
        "stale_remote",
        "missing_cover",
        "unmatched",
        "low_confidence_match",
    )

    fun show(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        onChanged: () -> Unit,
        initialIssueKind: String? = null,
        onCredentialRepairRequested: (List<String>) -> Boolean = { false },
    ) {
        val snapshot = repository.refreshDiagnostics()
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_library_maintenance, null, false)
        val summary = root.findViewById<TextView>(R.id.libraryMaintenanceSummary)
        val filter = root.findViewById<Spinner>(R.id.libraryMaintenanceFilter)
        val selectionSummary = root.findViewById<TextView>(R.id.libraryMaintenanceSelectionSummary)
        val selectPage = root.findViewById<MaterialButton>(R.id.libraryMaintenanceSelectPage)
        val selectFilter = root.findViewById<MaterialButton>(R.id.libraryMaintenanceSelectFilter)
        val clearSelection = root.findViewById<MaterialButton>(R.id.libraryMaintenanceClearSelection)
        val batchRescrape = root.findViewById<MaterialButton>(R.id.libraryMaintenanceBatchRescrape)
        val batchCredential = root.findViewById<MaterialButton>(R.id.libraryMaintenanceBatchCredential)
        val batchRecheck = root.findViewById<MaterialButton>(R.id.libraryMaintenanceBatchRecheck)
        val batchRemove = root.findViewById<MaterialButton>(R.id.libraryMaintenanceBatchRemove)
        val recycler = root.findViewById<RecyclerView>(R.id.libraryMaintenanceRecycler)
        val prev = root.findViewById<MaterialButton>(R.id.libraryMaintenancePrev)
        val next = root.findViewById<MaterialButton>(R.id.libraryMaintenanceNext)
        val pageLabel = root.findViewById<TextView>(R.id.libraryMaintenancePage)
        recycler.layoutManager = LinearLayoutManager(activity)
        val selectedKeys = linkedSetOf<String>()
        var dialog: AlertDialog? = null
        lateinit var refreshAll: () -> Unit
        lateinit var renderSelection: () -> Unit
        fun requestCredentialRepair(remoteIds: List<String>): Boolean {
            val ids = remoteIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) {
                Toast.makeText(activity, R.string.library_batch_credential_no_scope, Toast.LENGTH_LONG).show()
                return false
            }
            val opened = onCredentialRepairRequested(ids)
            if (opened) {
                dialog?.dismiss()
            } else {
                Toast.makeText(activity, R.string.library_issue_missing_remote_credential_unavailable, Toast.LENGTH_LONG).show()
            }
            return opened
        }
        val adapter = IssueAdapter(
            activity = activity,
            repository = repository,
            selectedKeys = selectedKeys,
            onSelectionChanged = { renderSelection() },
            onChanged = onChanged,
            onRefresh = { refreshAll() },
            onCredentialRepairRequested = { ids -> requestCredentialRepair(ids) },
        )
        recycler.adapter = adapter

        var allIssues = snapshot.issues
        var selectedKind: String? = initialIssueKind?.takeIf { it.isNotBlank() }
        var pageIndex = 0
        var options: List<FilterOption> = emptyList()
        var currentPage: List<LibraryIssue> = emptyList()

        fun filteredIssues(): List<LibraryIssue> =
            when (val kind = selectedKind) {
                null -> allIssues
                REPAIR_QUEUE_KIND -> allIssues.filter { it.kind in REPAIR_QUEUE_ISSUE_KINDS }
                else -> allIssues.filter { it.kind == kind }
            }

        fun filterStillExists(kind: String): Boolean =
            if (kind == REPAIR_QUEUE_KIND) {
                allIssues.any { it.kind in REPAIR_QUEUE_ISSUE_KINDS }
            } else {
                allIssues.any { it.kind == kind }
            }

        fun selectedIssues(): List<LibraryIssue> {
            val byKey = allIssues.associateBy { issueKey(it) }
            return selectedKeys.mapNotNull { byKey[it] }
        }

        fun maxPage(total: Int): Int =
            if (total <= 0) 0 else (total - 1) / PAGE_SIZE

        fun sourceCount(): Int =
            repository.store.readLocalRootUris().size + repository.store.readRemotesList().size

        renderSelection = {
            val filtered = filteredIssues()
            val selected = selectedIssues()
            val selectedPathCount = selected.map { it.path }.filter { it.isNotBlank() }.toSet().size
            selectionSummary.text = activity.getString(
                R.string.library_batch_selection_fmt,
                selected.size,
                selectedPathCount,
                filtered.size,
            )
            selectPage.isEnabled = currentPage.isNotEmpty()
            selectFilter.isEnabled = filtered.isNotEmpty()
            clearSelection.isEnabled = selected.isNotEmpty()
            batchRescrape.isEnabled = selected.isNotEmpty()
            batchCredential.isEnabled = missingCredentialRemoteIds(selected).isNotEmpty()
            batchRemove.isEnabled = selected.any { it.kind == "stale_remote" }
            batchRecheck.isEnabled = sourceCount() > 0
        }

        fun notifySelectionChanged() {
            adapter.notifyDataSetChanged()
            renderSelection()
        }

        fun renderPage() {
            val filtered = filteredIssues()
            pageIndex = pageIndex.coerceIn(0, maxPage(filtered.size))
            val from = pageIndex * PAGE_SIZE
            val page = filtered.drop(from).take(PAGE_SIZE)
            currentPage = page
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
            renderSelection()
        }

        fun rebuildFilterOptions() {
            val counts = allIssues.groupingBy { it.kind }.eachCount()
            options = buildList {
                add(FilterOption(null, activity.getString(R.string.library_maintenance_filter_all, allIssues.size)))
                val repairCount = allIssues.count { it.kind in REPAIR_QUEUE_ISSUE_KINDS }
                if (repairCount > 0) {
                    add(FilterOption(REPAIR_QUEUE_KIND, activity.getString(R.string.library_maintenance_filter_repair_queue, repairCount)))
                }
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

        fun applySnapshot(nextSnapshot: LibraryDiagnosticsSnapshot) {
            allIssues = nextSnapshot.issues
            val liveKeys = allIssues.mapTo(mutableSetOf()) { issueKey(it) }
            selectedKeys.retainAll(liveKeys)
            if (selectedKind != null && !filterStillExists(selectedKind.orEmpty())) {
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
        selectPage.setOnClickListener {
            selectedKeys.addAll(currentPage.map { issueKey(it) })
            notifySelectionChanged()
        }
        selectFilter.setOnClickListener {
            selectedKeys.addAll(filteredIssues().map { issueKey(it) })
            notifySelectionChanged()
        }
        clearSelection.setOnClickListener {
            selectedKeys.clear()
            notifySelectionChanged()
        }
        batchRescrape.setOnClickListener {
            val issues = selectedIssues()
            if (issues.isEmpty()) {
                Toast.makeText(activity, R.string.library_batch_need_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmBatchRescrape(activity, issues) {
                selectedKeys.clear()
                renderPage()
            }
        }
        batchCredential.setOnClickListener {
            val issues = selectedIssues()
            if (issues.isEmpty()) {
                Toast.makeText(activity, R.string.library_batch_need_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmBatchCredentialRepair(activity, issues) { ids ->
                requestCredentialRepair(ids)
            }
        }
        batchRemove.setOnClickListener {
            val issues = selectedIssues()
            if (issues.isEmpty()) {
                Toast.makeText(activity, R.string.library_batch_need_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val staleIssues = issues.filter { it.kind == "stale_remote" }.distinctBy { it.path }
            if (staleIssues.isEmpty()) {
                Toast.makeText(activity, R.string.library_batch_remove_no_stale, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val skipped = issues.count { it.kind != "stale_remote" }
            MvDialog.show(
                MvDialog.builder(activity)
                    .setTitle(R.string.library_batch_remove_title)
                    .setMessage(activity.getString(R.string.library_batch_remove_confirm_fmt, staleIssues.size, skipped))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        activity.lifecycleScope.launch {
                            repository.removeItemsByPaths(staleIssues.map { it.path })
                                .onSuccess { result ->
                                    staleIssues.forEach { selectedKeys.remove(issueKey(it)) }
                                    onChanged()
                                    applySnapshot(repository.diagnostics.value)
                                    MvDialog.show(
                                        MvDialog.builder(activity)
                                            .setTitle(R.string.library_batch_remove_title)
                                            .setMessage(
                                                activity.getString(
                                                    R.string.library_batch_remove_result_fmt,
                                                    result.requestedPathCount,
                                                    result.matchedPathCount,
                                                    result.removedItemCount,
                                                    result.missingPathCount,
                                                ),
                                            )
                                            .setPositiveButton(android.R.string.ok, null),
                                    )
                                }
                                .onFailure { e ->
                                    Toast.makeText(activity, e.message ?: activity.getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null),
            )
        }
        batchRecheck.setOnClickListener {
            val count = sourceCount()
            MvDialog.show(
                MvDialog.builder(activity)
                    .setTitle(R.string.library_batch_recheck_title)
                    .setMessage(activity.getString(R.string.library_batch_recheck_confirm_fmt, count))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Toast.makeText(activity, R.string.library_batch_recheck_running, Toast.LENGTH_SHORT).show()
                        batchRecheck.isEnabled = false
                        activity.lifecycleScope.launch {
                            val nextSnapshot = withContext(Dispatchers.IO) {
                                repository.refreshDiagnostics(probeSources = true)
                            }
                            onChanged()
                            applySnapshot(nextSnapshot)
                            val ok = nextSnapshot.sourceHealth.count { it.reachable == true }
                            val bad = nextSnapshot.sourceHealth.count { it.reachable == false }
                            val unchecked = nextSnapshot.sourceHealth.count { it.reachable == null }
                            MvDialog.show(
                                MvDialog.builder(activity)
                                    .setTitle(R.string.library_batch_recheck_title)
                                    .setMessage(
                                        activity.getString(
                                            R.string.library_batch_recheck_result_fmt,
                                            nextSnapshot.sourceHealth.size,
                                            ok,
                                            bad,
                                            unchecked,
                                        ),
                                    )
                                    .setPositiveButton(android.R.string.ok, null),
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null),
            )
        }
        applySnapshot(snapshot)
        dialog = MvDialog.showStyled(
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
        private val selectedKeys: MutableSet<String>,
        private val onSelectionChanged: () -> Unit,
        private val onChanged: () -> Unit,
        private val onRefresh: () -> Unit,
        private val onCredentialRepairRequested: (List<String>) -> Boolean,
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
            val key = issueKey(issue)
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = key in selectedKeys
            holder.check.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedKeys.add(key)
                } else {
                    selectedKeys.remove(key)
                }
                onSelectionChanged()
            }
            holder.itemView.setOnClickListener {
                holder.check.toggle()
            }
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
            val credentialRemoteIds = missingCredentialRemoteIds(listOf(issue))
            val canRepairCredential = credentialRemoteIds.isNotEmpty()
            holder.rescrape.visibility = if (canRepairCredential) View.GONE else View.VISIBLE
            holder.credential.visibility = if (canRepairCredential) View.VISIBLE else View.GONE
            holder.credential.setOnClickListener {
                onCredentialRepairRequested(credentialRemoteIds)
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
            val cfg = app.repository.store.readRemotesList().firstOrNull { it.id == parsed.configId }
            if (cfg?.credentialMissing == true) {
                Toast.makeText(activity, R.string.library_issue_missing_remote_credential_action, Toast.LENGTH_LONG).show()
                return true
            }
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
                                    selectedKeys.remove(issueKey(issue))
                                    onSelectionChanged()
                                    onChanged()
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
        val check: CheckBox = view.findViewById(R.id.libraryIssueCheck)
        val title: TextView = view.findViewById(R.id.libraryIssueTitle)
        val meta: TextView = view.findViewById(R.id.libraryIssueMeta)
        val open: MaterialButton = view.findViewById(R.id.libraryIssueOpen)
        val evidence: MaterialButton = view.findViewById(R.id.libraryIssueEvidence)
        val rescrape: MaterialButton = view.findViewById(R.id.libraryIssueRescrape)
        val credential: MaterialButton = view.findViewById(R.id.libraryIssueCredential)
        val remove: MaterialButton = view.findViewById(R.id.libraryIssueRemove)
    }

    private fun confirmBatchCredentialRepair(
        activity: AppCompatActivity,
        issues: List<LibraryIssue>,
        onConfirmed: (List<String>) -> Boolean,
    ) {
        val remoteIds = missingCredentialRemoteIds(issues)
        if (remoteIds.isEmpty()) {
            Toast.makeText(activity, R.string.library_batch_credential_no_scope, Toast.LENGTH_LONG).show()
            return
        }
        val credentialIssues = issues.count {
            it.kind == "missing_remote_credential" && RemotePath.parse(it.path)?.configId?.isNotBlank() == true
        }
        val skipped = issues.size - credentialIssues
        MvDialog.show(
            MvDialog.builder(activity)
                .setTitle(R.string.library_batch_credential_title)
                .setMessage(
                    activity.getString(
                        R.string.library_batch_credential_confirm_fmt,
                        remoteIds.size,
                        credentialIssues,
                        skipped,
                    ),
                )
                .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed(remoteIds) }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun confirmBatchRescrape(
        activity: AppCompatActivity,
        issues: List<LibraryIssue>,
        afterStarted: () -> Unit,
    ) {
        val app = activity.application as MediaVaultApp
        val plan = buildBatchRescrapePlan(app.repository, issues)
        if (plan.scopedPaths.isEmpty()) {
            Toast.makeText(activity, R.string.library_batch_rescrape_no_scope, Toast.LENGTH_LONG).show()
            return
        }
        if (app.scrapeManager.isRunning()) {
            Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        MvDialog.show(
            MvDialog.builder(activity)
                .setTitle(R.string.library_batch_rescrape_title)
                .setMessage(
                    activity.getString(
                        R.string.library_batch_rescrape_confirm_fmt,
                        plan.scopedPaths.size,
                        plan.localRoots.size,
                        plan.remoteIds.size,
                        plan.skippedCount,
                    ),
                )
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (app.scrapeManager.isRunning()) {
                        Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    app.repository.store.clearScrapeRecordPaths(plan.scopedPaths)
                    app.scrapeManager.start(
                        rebuild = false,
                        localRootUris = plan.localRoots.takeIf { it.isNotEmpty() },
                        remoteIds = plan.remoteIds.takeIf { it.isNotEmpty() },
                    )
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.library_batch_rescrape_started_fmt,
                            plan.scopedPaths.size,
                            plan.localRoots.size,
                            plan.remoteIds.size,
                            plan.skippedCount,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    afterStarted()
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun buildBatchRescrapePlan(
        repository: LibraryRepository,
        issues: List<LibraryIssue>,
    ): BatchRescrapePlan {
        val localRoots = repository.store.readLocalRootUris().sortedByDescending { it.length }
        val remoteById = repository.store.readRemotesList().associateBy { it.id }
        val scopedPaths = linkedSetOf<String>()
        val scopedLocalRoots = linkedSetOf<String>()
        val scopedRemoteIds = linkedSetOf<String>()
        var skipped = 0
        issues
            .map { it.path.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { path ->
                val parsed = RemotePath.parse(path)
                when {
                    parsed != null && remoteById[parsed.configId]?.credentialMissing == true -> {
                        skipped++
                    }
                    parsed != null && parsed.configId in remoteById -> {
                        scopedPaths.add(path)
                        scopedRemoteIds.add(parsed.configId)
                    }
                    parsed != null -> {
                        skipped++
                    }
                    else -> {
                        val root = localRoots.firstOrNull { path.startsWith(it) }
                        if (root == null) {
                            skipped++
                        } else {
                            scopedPaths.add(path)
                            scopedLocalRoots.add(root)
                        }
                    }
                }
            }
        return BatchRescrapePlan(
            scopedPaths = scopedPaths.toList(),
            localRoots = scopedLocalRoots.toList(),
            remoteIds = scopedRemoteIds.toList(),
            skippedCount = skipped,
        )
    }

    private data class BatchRescrapePlan(
        val scopedPaths: List<String>,
        val localRoots: List<String>,
        val remoteIds: List<String>,
        val skippedCount: Int,
    )

    private fun missingCredentialRemoteIds(issues: List<LibraryIssue>): List<String> =
        issues.asSequence()
            .filter { it.kind == "missing_remote_credential" }
            .mapNotNull { RemotePath.parse(it.path)?.configId }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun issueKey(issue: LibraryIssue): String = issue.kind + "\u001F" + issue.path

    fun issueLabel(context: Context, kind: String): String {
        val resId = when (kind) {
            "missing_path" -> R.string.library_issue_missing_path
            "missing_remote_credential" -> R.string.library_issue_missing_remote_credential
            "duplicate_path" -> R.string.library_issue_duplicate_path
            "duplicate_title" -> R.string.library_issue_duplicate_title
            "stale_remote" -> R.string.library_issue_stale_remote
            "missing_cover" -> R.string.library_issue_missing_cover
            "unmatched" -> R.string.library_issue_unmatched
            "low_confidence_match" -> R.string.library_issue_low_confidence_match
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else kind
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
