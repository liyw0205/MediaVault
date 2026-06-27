package com.mediavault.ui

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mediavault.R
import com.mediavault.data.LibraryRepository
import com.mediavault.data.ScrapeConfig
import com.mediavault.data.ScrapeSettings

object ScrapeDrawerBinder {
    fun bind(
        activity: AppCompatActivity,
        drawer: DrawerLayout,
        panelRoot: View,
        repository: LibraryRepository,
        onOpenManageDirs: () -> Unit,
        onRootsMayHaveChanged: () -> Unit,
    ) {
        val coverFiles = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFilesSwitch)
        val coverFrame = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFrameSwitch)
        val nfo = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeNfoSwitch)
        val filename = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeFilenameSwitch)
        val subs = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeSubtitlesSwitch)
        val threadsSlider = panelRoot.findViewById<Slider>(R.id.drawerScrapeThreadsSlider)
        val threadsLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeThreadsLabel)
        val remoteSlider = panelRoot.findViewById<Slider>(R.id.drawerScrapeRemoteFrameSlider)
        val remoteLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeRemoteFrameLabel)
        val saveBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerSaveScrapeSettingsBtn)
        val manageDirs = panelRoot.findViewById<MaterialButton>(R.id.drawerManageDirsBtn)
        val dataBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerOpenDataBtn)

        fun loadUi(s: ScrapeSettings) {
            val cfg = s.normalized()
            coverFiles.isChecked = cfg.coverFromFiles
            coverFrame.isChecked = cfg.coverFromVideoFrame
            nfo.isChecked = cfg.metadataFromNfo
            filename.isChecked = cfg.metadataFromFilename
            subs.isChecked = cfg.scanSidecarSubtitles
            threadsSlider.value = cfg.threadCount.toFloat()
            threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, cfg.threadCount)
            remoteSlider.value = cfg.remoteFrameConcurrency.toFloat()
            remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, cfg.remoteFrameConcurrency)
        }

        loadUi(ScrapeConfig.readSettings(activity))

        threadsSlider.addOnChangeListener { _, value, _ ->
            threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, value.toInt())
        }
        remoteSlider.addOnChangeListener { _, value, _ ->
            remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, value.toInt())
        }

        saveBtn.setOnClickListener {
            val next = ScrapeSettings(
                scrapeMode = ScrapeConfig.MODE_LOCAL,
                threadCount = threadsSlider.value.toInt(),
                remoteFrameConcurrency = remoteSlider.value.toInt(),
                coverFromFiles = coverFiles.isChecked,
                coverFromVideoFrame = coverFrame.isChecked,
                metadataFromNfo = nfo.isChecked,
                metadataFromFilename = filename.isChecked,
                scanSidecarSubtitles = subs.isChecked,
            ).normalized()
            ScrapeConfig.writeSettings(activity, next)
            loadUi(next)
            Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        manageDirs.setOnClickListener {
            drawer.closeDrawer(GravityCompat.END, false)
            onOpenManageDirs()
        }

        dataBtn.setOnClickListener {
            drawer.closeDrawer(GravityCompat.END, false)
            DataStorageDialog.show(activity, repository) { onRootsMayHaveChanged() }
        }
    }

    fun reloadOptions(activity: AppCompatActivity, panelRoot: View) {
        val threadsLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeThreadsLabel)
        val remoteLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeRemoteFrameLabel)
        val cfg = ScrapeConfig.readSettings(activity).normalized()
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFilesSwitch).isChecked = cfg.coverFromFiles
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFrameSwitch).isChecked = cfg.coverFromVideoFrame
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeNfoSwitch).isChecked = cfg.metadataFromNfo
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeFilenameSwitch).isChecked = cfg.metadataFromFilename
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeSubtitlesSwitch).isChecked = cfg.scanSidecarSubtitles
        panelRoot.findViewById<Slider>(R.id.drawerScrapeThreadsSlider).value = cfg.threadCount.toFloat()
        threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, cfg.threadCount)
        panelRoot.findViewById<Slider>(R.id.drawerScrapeRemoteFrameSlider).value = cfg.remoteFrameConcurrency.toFloat()
        remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, cfg.remoteFrameConcurrency)
    }
}