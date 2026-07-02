package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.ExportArchive
import com.mediavault.data.ExportBundleWriter
import com.mediavault.data.LibraryRepository
import com.mediavault.remote.RemoteErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BackupExportDialog {
    fun show(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        onArchiveReady: (ExportArchive) -> Unit,
    ) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_backup_export, null)
        val stats = root.findViewById<TextView>(R.id.backupExportStats)
        val status = root.findViewById<TextView>(R.id.backupExportStatus)
        val backupBtn = root.findViewById<MaterialButton>(R.id.backupExportBackupBtn)
        val diagnosticsBtn = root.findViewById<MaterialButton>(R.id.backupExportDiagnosticsBtn)
        stats.text = statsText(activity, repository)

        val dialog = MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(R.string.backup_export_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = root,
        )

        fun setBusy(busy: Boolean, textRes: Int) {
            backupBtn.isEnabled = !busy
            diagnosticsBtn.isEnabled = !busy
            status.visibility = if (busy) View.VISIBLE else View.GONE
            status.setText(textRes)
        }

        fun createArchive(textRes: Int, block: ExportBundleWriter.() -> ExportArchive) {
            setBusy(true, textRes)
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ExportBundleWriter(activity).block() }
                }
                setBusy(false, textRes)
                result
                    .onSuccess { archive ->
                        Toast.makeText(activity, archive.summary, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        onArchiveReady(archive)
                    }
                    .onFailure { e ->
                        Toast.makeText(
                            activity,
                            RemoteErrorMessages.userMessage(activity, e),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }

        backupBtn.setOnClickListener {
            createArchive(R.string.backup_export_generating_backup) {
                createBackupArchive(repository)
            }
        }
        diagnosticsBtn.setOnClickListener {
            createArchive(R.string.backup_export_generating_diagnostics) {
                createDiagnosticsArchive(repository)
            }
        }
    }

    private fun statsText(activity: AppCompatActivity, repository: LibraryRepository): String {
        val d = repository.dataSizes()
        val snapshot = repository.diagnostics.value
        val roots = repository.store.readLocalRootUris().size
        val remotes = repository.store.readRemotesList().size
        return activity.getString(
            R.string.backup_export_stats_fmt,
            snapshot.itemCount,
            roots,
            remotes,
            snapshot.totalIssues,
            LibraryUi.formatBytes(d.libraryBytes),
            LibraryUi.formatBytes(d.scrapeRecordBytes),
        )
    }
}
