package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.LibraryIssue
import com.mediavault.data.LibraryRepository
import kotlinx.coroutines.launch

object LibraryMaintenanceDialog {
    fun show(activity: AppCompatActivity, repository: LibraryRepository, onChanged: () -> Unit) {
        val snapshot = repository.refreshDiagnostics()
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_library_maintenance, null, false)
        val summary = root.findViewById<TextView>(R.id.libraryMaintenanceSummary)
        val recycler = root.findViewById<RecyclerView>(R.id.libraryMaintenanceRecycler)
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
            append(activity.getString(R.string.library_maintenance_sample_hint, snapshot.sampleIssues.size))
        }
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = IssueAdapter(activity, repository, snapshot.sampleIssues, onChanged)
        MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.library_maintenance_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = root,
        )
    }

    private class IssueAdapter(
        private val activity: AppCompatActivity,
        private val repository: LibraryRepository,
        private val issues: List<LibraryIssue>,
        private val onChanged: () -> Unit,
    ) : RecyclerView.Adapter<IssueVH>() {
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
            holder.remove.setOnClickListener {
                confirmRemove(issue)
            }
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
}
