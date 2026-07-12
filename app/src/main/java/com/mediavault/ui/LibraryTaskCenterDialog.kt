package com.mediavault.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.LibraryTaskEntry
import com.mediavault.data.LibraryTaskStore

object LibraryTaskCenterDialog {
    fun show(
        activity: AppCompatActivity,
        onChanged: () -> Unit = {},
    ) {
        val store = LibraryTaskStore(activity)
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_library_task_center, null, false)
        val summary = root.findViewById<TextView>(R.id.taskCenterSummary)
        val filter = root.findViewById<Spinner>(R.id.taskCenterFilter)
        val empty = root.findViewById<TextView>(R.id.taskCenterEmpty)
        val clear = root.findViewById<MaterialButton>(R.id.taskCenterClearFinished)
        val recycler = root.findViewById<RecyclerView>(R.id.taskCenterRecycler)
        var dialog: AlertDialog? = null
        var selectedFilter = TaskFilter.ALL
        val filterOptions = listOf(
            FilterOption(TaskFilter.ALL, activity.getString(R.string.task_center_filter_all)),
            FilterOption(TaskFilter.RUNNING, activity.getString(R.string.task_center_filter_running)),
            FilterOption(TaskFilter.ATTENTION, activity.getString(R.string.task_center_filter_attention)),
            FilterOption(TaskFilter.FAILED, activity.getString(R.string.task_center_filter_failed)),
            FilterOption(TaskFilter.ACTIONABLE, activity.getString(R.string.task_center_filter_actionable)),
        )

        fun openIssue(issueKind: String) {
            dialog?.dismiss()
            (activity as? MainActivity)?.openLibraryMaintenance(issueKind)
        }

        val adapter = TaskAdapter(
            activity = activity,
            onOpenIssue = { issueKind -> openIssue(issueKind) },
            onOpenDetail = { task -> showTaskDetail(activity, task) { issueKind -> openIssue(issueKind) } },
        )
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        filter.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            filterOptions.map { it.label },
        )

        fun render() {
            val tasks = store.read()
            val running = tasks.count { it.status == LibraryTaskStore.STATUS_RUNNING }
            val attention = tasks.count { requiresAttention(it) }
            val latest = tasks.firstOrNull()
            val visibleTasks = tasks.filter { selectedFilter.accepts(it) }
            val filterLabel = filterOptions.firstOrNull { it.filter == selectedFilter }?.label.orEmpty()
            summary.text = if (latest == null) {
                activity.getString(R.string.task_center_summary_empty)
            } else {
                activity.getString(
                    R.string.task_center_summary_fmt,
                    tasks.size,
                    running,
                    attention,
                    filterLabel,
                    visibleTasks.size,
                    latest.updatedAt,
                    latest.title,
                    statusLabel(activity, latest.status),
                )
            }
            empty.text = activity.getString(
                if (tasks.isEmpty()) R.string.task_center_empty else R.string.task_center_empty_filtered,
            )
            empty.isVisible = visibleTasks.isEmpty()
            recycler.isVisible = visibleTasks.isNotEmpty()
            clear.isEnabled = tasks.any { it.status != LibraryTaskStore.STATUS_RUNNING }
            adapter.submit(visibleTasks)
        }

        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFilter = filterOptions.getOrNull(position)?.filter ?: TaskFilter.ALL
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        clear.setOnClickListener {
            val removed = store.clearFinished()
            Toast.makeText(activity, activity.getString(R.string.task_center_cleared_fmt, removed), Toast.LENGTH_SHORT).show()
            render()
            onChanged()
        }

        render()
        dialog = MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.task_center_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null),
            inputRoot = root,
        )
    }

    fun statusLabel(context: Context, status: String): String {
        val resId = when (status) {
            LibraryTaskStore.STATUS_RUNNING -> R.string.task_status_running
            LibraryTaskStore.STATUS_SUCCESS -> R.string.task_status_success
            LibraryTaskStore.STATUS_PARTIAL -> R.string.task_status_partial
            LibraryTaskStore.STATUS_FAILED -> R.string.task_status_failed
            LibraryTaskStore.STATUS_CANCELLED -> R.string.task_status_cancelled
            else -> 0
        }
        return if (resId == 0) status else context.getString(resId)
    }

    private fun typeLabel(context: Context, type: String): String {
        val resId = when (type) {
            LibraryTaskStore.TYPE_SCRAPE -> R.string.task_type_scrape
            LibraryTaskStore.TYPE_SOURCE_RECHECK -> R.string.task_type_source_recheck
            LibraryTaskStore.TYPE_IMPORT_BACKUP -> R.string.task_type_import_backup
            LibraryTaskStore.TYPE_BATCH_REMOVE -> R.string.task_type_batch_remove
            else -> 0
        }
        return if (resId == 0) type.ifBlank { context.getString(R.string.task_detail_empty_value) } else context.getString(resId)
    }

    private fun showTaskDetail(
        activity: AppCompatActivity,
        task: LibraryTaskEntry,
        onOpenIssue: (String) -> Unit,
    ) {
        val issueKind = task.issueKind?.takeIf { it.isNotBlank() }
        val issueText = issueKind
            ?.let { LibraryMaintenanceDialog.issueLabel(activity, it) }
            ?: activity.getString(R.string.task_detail_issue_none)
        val empty = activity.getString(R.string.task_detail_empty_value)
        val message = buildString {
            append(
                activity.getString(
                    R.string.task_detail_message_fmt,
                    task.title.ifBlank { empty },
                    typeLabel(activity, task.type),
                    statusLabel(activity, task.status),
                    task.createdAt.ifBlank { empty },
                    task.updatedAt.ifBlank { empty },
                    task.localScopeCount,
                    task.remoteScopeCount,
                    task.summary.ifBlank { empty },
                    task.detail.ifBlank { empty },
                    issueText,
                ),
            )
            task.statistics.takeUnless { it.isEmpty() }?.let { stats ->
                append("\n\n")
                append(activity.getString(
                    R.string.task_detail_statistics_fmt,
                    stats.discoveredCount,
                    stats.writtenCount,
                    stats.skippedCount,
                    stats.issueCount,
                    stats.tmdbHitCount,
                    stats.tmdbMissCount,
                    stats.coverAddedCount,
                ))
            }
            task.failureSummary?.let { failure ->
                append("\n\n")
                append(activity.getString(
                    R.string.task_detail_failure_fmt,
                    task.failureCategory ?: activity.getString(R.string.task_detail_empty_value),
                    failure,
                ))
            }
            if (requiresAttention(task) && issueKind == null) {
                append("\n\n")
                append(activity.getString(R.string.task_detail_no_action))
            }
        }
        val builder = MvDialog.builder(activity)
            .setTitle(R.string.task_detail_title)
            .setMessage(message)
        if (LibraryTaskStore.canSafelyRetry(task)) {
            builder.setNeutralButton(R.string.task_detail_retry) { _, _ ->
                val manager = (activity.application as MediaVaultApp).scrapeManager
                if (manager.isRunning()) {
                    Toast.makeText(activity, R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
                } else {
                    manager.start(
                        rebuild = false,
                        taskTitle = activity.getString(R.string.task_detail_retry_title_fmt, task.title),
                        taskDetail = activity.getString(R.string.task_detail_retry_detail_fmt, task.id),
                        taskIssueKind = task.issueKind,
                    )
                    Toast.makeText(activity, R.string.task_detail_retry_started, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (issueKind == null) {
            builder.setPositiveButton(android.R.string.ok, null)
        } else {
            builder
                .setPositiveButton(R.string.task_item_open_issues) { _, _ -> onOpenIssue(issueKind) }
                .setNegativeButton(android.R.string.cancel, null)
        }
        MvDialog.show(builder)
    }

    private fun requiresAttention(task: LibraryTaskEntry): Boolean =
        task.status == LibraryTaskStore.STATUS_PARTIAL ||
            task.status == LibraryTaskStore.STATUS_FAILED ||
            task.status == LibraryTaskStore.STATUS_CANCELLED

    private enum class TaskFilter {
        ALL,
        RUNNING,
        ATTENTION,
        FAILED,
        ACTIONABLE;

        fun accepts(task: LibraryTaskEntry): Boolean =
            when (this) {
                ALL -> true
                RUNNING -> task.status == LibraryTaskStore.STATUS_RUNNING
                ATTENTION -> task.status == LibraryTaskStore.STATUS_PARTIAL ||
                    task.status == LibraryTaskStore.STATUS_FAILED ||
                    task.status == LibraryTaskStore.STATUS_CANCELLED
                FAILED -> task.status == LibraryTaskStore.STATUS_FAILED
                ACTIONABLE -> task.issueKind?.isNotBlank() == true
            }
    }

    private data class FilterOption(
        val filter: TaskFilter,
        val label: String,
    )

    private class TaskAdapter(
        private val activity: AppCompatActivity,
        private val onOpenIssue: (String) -> Unit,
        private val onOpenDetail: (LibraryTaskEntry) -> Unit,
    ) : RecyclerView.Adapter<TaskVH>() {
        private var items: List<LibraryTaskEntry> = emptyList()

        fun submit(next: List<LibraryTaskEntry>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_task, parent, false)
            return TaskVH(view)
        }

        override fun onBindViewHolder(holder: TaskVH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.meta.text = activity.getString(
                R.string.task_item_meta_fmt,
                statusLabel(activity, item.status),
                item.updatedAt,
                item.localScopeCount,
                item.remoteScopeCount,
            )
            holder.summary.text = item.summary
            holder.detail.text = item.detail
            holder.detail.isVisible = item.detail.isNotBlank()
            holder.itemView.setOnClickListener { onOpenDetail(item) }
            holder.openDetail.setOnClickListener { onOpenDetail(item) }
            holder.openIssues.isVisible = item.issueKind?.isNotBlank() == true
            holder.openIssues.setOnClickListener {
                item.issueKind?.let(onOpenIssue)
            }
        }
    }

    private class TaskVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.taskItemTitle)
        val meta: TextView = view.findViewById(R.id.taskItemMeta)
        val summary: TextView = view.findViewById(R.id.taskItemSummary)
        val detail: TextView = view.findViewById(R.id.taskItemDetail)
        val openDetail: MaterialButton = view.findViewById(R.id.taskItemDetailAction)
        val openIssues: MaterialButton = view.findViewById(R.id.taskItemOpenIssues)
    }
}
