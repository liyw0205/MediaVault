package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mediavault.R
import com.mediavault.data.LibraryRepository
import kotlinx.coroutines.launch

object DataStorageDialog {
    fun show(
        activity: AppCompatActivity,
        repository: LibraryRepository,
        onLibraryCleared: () -> Unit = {},
    ) {
        val d = repository.dataSizes()
        val msg = buildString {
            activity.apply {
                append(getString(R.string.data_library))
                append("：")
                append(LibraryUi.formatBytes(d.libraryBytes))
                append(" · ")
                append(d.videoCount)
                append(" 条\n")
                append(getString(R.string.data_covers))
                append("：")
                append(LibraryUi.formatBytes(d.coverBytes))
                append(" · ")
                append(d.coverCount)
                append(" 张\n")
                append(getString(R.string.data_scrape))
                append("：")
                append(LibraryUi.formatBytes(d.scrapeRecordBytes))
                append("\n")
                append(getString(R.string.data_remote_stream))
                append("：")
                append(LibraryUi.formatBytes(d.remoteStreamBytes))
                append(" · ")
                append(d.remoteStreamFiles)
                append(" 个文件")
            }
        }
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_data, null)
        root.findViewById<TextView>(R.id.dataDialogStats).text = msg
        val builder = MvDialog.builder(activity)
            .setTitle(R.string.data_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
        val dialog = MvDialog.showStyled(builder)
        root.findViewById<View>(R.id.dataClearCovers).setOnClickListener {
            confirm(activity, activity.getString(R.string.confirm_clear_covers)) {
                val n = repository.clearCovers()
                Toast.makeText(activity, activity.getString(R.string.data_cleared_covers_fmt, n), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearScrape).setOnClickListener {
            confirm(activity, activity.getString(R.string.confirm_clear_scrape)) {
                repository.clearScrapeRecord()
                Toast.makeText(activity, R.string.data_cleared_scrape, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearRemoteStream).setOnClickListener {
            confirm(activity, activity.getString(R.string.confirm_clear_remote)) {
                val n = repository.clearRemoteStreamCache()
                Toast.makeText(activity, activity.getString(R.string.data_cleared_remote_fmt, n), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearLibrary).setOnClickListener {
            confirm(activity, activity.getString(R.string.confirm_clear_library)) {
                activity.lifecycleScope.launch {
                    repository.clearLibraryJson()
                        .onSuccess {
                            Toast.makeText(activity, R.string.data_cleared_library, Toast.LENGTH_SHORT).show()
                            onLibraryCleared()
                            dialog.dismiss()
                        }
                        .onFailure { e ->
                            Toast.makeText(
                                activity,
                                e.message ?: activity.getString(R.string.action_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            }
        }
    }

    private fun confirm(activity: AppCompatActivity, message: String, onOk: () -> Unit) {
        MvDialog.builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}