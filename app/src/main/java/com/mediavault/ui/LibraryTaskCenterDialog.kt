package com.mediavault.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
        val empty = root.findViewById<TextView>(R.id.taskCenterEmpty)
        val clear = root.findViewById<MaterialButton>(R.id.taskCenterClearFinished)
        val recycler = root.findViewById<RecyclerView>(R.id.taskCenterRecycler)
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val adapter = TaskAdapter(activity) { issueKind ->
            dialog?.dismiss()
            (activity as? MainActivity)?.openLibraryMaintenance(issueKind)
        }
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        fun render() {
            val tasks = store.read()
            val running = tasks.count { it.status == LibraryTaskStore.STATUS_RUNNING }
            val latest = tasks.firstOrNull()
            summary.text = if (latest == null) {
                activity.getString(R.string.task_center_summary_empty)
            } else {
                activity.getString(
                    R.string.task_center_summary_fmt,
                    tasks.size,
                    running,
                    latest.updatedAt,
                    latest.title,
                    statusLabel(activity, latest.status),
                )
            }
            empty.isVisible = tasks.isEmpty()
            recycler.isVisible = tasks.isNotEmpty()
            clear.isEnabled = tasks.any { it.status != LibraryTaskStore.STATUS_RUNNING }
            adapter.submit(tasks)
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

    private class TaskAdapter(
        private val activity: AppCompatActivity,
        private val onOpenIssue: (String) -> Unit,
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
        val openIssues: MaterialButton = view.findViewById(R.id.taskItemOpenIssues)
    }
}
