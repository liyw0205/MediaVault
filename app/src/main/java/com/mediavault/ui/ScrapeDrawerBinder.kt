package com.mediavault.ui

import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mediavault.R
import com.mediavault.data.LibraryRepository
import com.mediavault.data.LibraryDiagnosticsSnapshot
import com.mediavault.data.SourceHealth
import com.mediavault.data.ScrapeConfig
import com.mediavault.data.ScrapeSettings
import com.mediavault.data.TmdbClient
import com.mediavault.data.TmdbDiskCache
import com.mediavault.data.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ScrapeDrawerBinder {

    private val totalCacheMbSteps = intArrayOf(256, 512, 1024, 2048, 3072, 4096, 6144, 8192)
    private val perFileCacheMbSteps = intArrayOf(64, 128, 256, 512, 768, 1024, 2048, 4096)

    private fun mbToTotalStep(mb: Int): Float {
        val idx = totalCacheMbSteps.indexOfFirst { it >= mb }.let { if (it < 0) totalCacheMbSteps.lastIndex else it }
        return (idx + 1).toFloat()
    }

    private fun totalStepToBytes(step: Int): Long {
        val idx = (step - 1).coerceIn(0, totalCacheMbSteps.lastIndex)
        return totalCacheMbSteps[idx].toLong() * 1024 * 1024
    }

    private fun mbToPerFileStep(mb: Int): Float {
        val idx = perFileCacheMbSteps.indexOfFirst { it >= mb }.let { if (it < 0) perFileCacheMbSteps.lastIndex else it }
        return (idx + 1).toFloat()
    }

    private fun perFileStepToBytes(step: Int): Long {
        val idx = (step - 1).coerceIn(0, perFileCacheMbSteps.lastIndex)
        return perFileCacheMbSteps[idx].toLong() * 1024 * 1024
    }

    private fun formatMbLabel(activity: AppCompatActivity, mb: Int, resId: Int): String {
        val text = if (mb >= 1024) {
            val g = mb / 1024f
            if (g == g.toInt().toFloat()) "${g.toInt()} GB" else "%.1f GB".format(g)
        } else {
            "$mb MB"
        }
        return activity.getString(resId, text)
    }

    private fun bindSubtitleLangRadio(group: RadioGroup, lang: SubtitlePrefs.PrimaryLang) {
        val id = when (lang) {
            SubtitlePrefs.PrimaryLang.HANT_FIRST -> R.id.drawerSubtitleLangHant
            SubtitlePrefs.PrimaryLang.EN_FIRST -> R.id.drawerSubtitleLangEn
            SubtitlePrefs.PrimaryLang.NEUTRAL -> R.id.drawerSubtitleLangNeutral
            SubtitlePrefs.PrimaryLang.HANS_FIRST -> R.id.drawerSubtitleLangHans
        }
        group.check(id)
    }

    private var directoriesPanel: ScrapeDirectoriesPanelController? = null
    private var onRootsCallback: (() -> Unit)? = null
    private var boundRepository: LibraryRepository? = null
    private var pickLocalTreeCallback: (() -> Unit)? = null

    fun onLocalTreePicked(uri: android.net.Uri?) {
        directoriesPanel?.onLocalTreePicked(uri)
    }

    fun rebindViews(
        activity: AppCompatActivity,
        drawer: DrawerLayout,
        panelRoot: View,
    ) {
        val repo = boundRepository ?: return
        val cb = onRootsCallback ?: return
        val pick = pickLocalTreeCallback ?: return
        bind(activity, panelRoot, repo, cb, pick, drawer = drawer)
    }

    fun bind(
        activity: AppCompatActivity,
        panelRoot: View,
        repository: LibraryRepository,
        onRootsMayHaveChanged: () -> Unit,
        pickLocalTree: () -> Unit,
        drawer: DrawerLayout? = null,
        includeDirectories: Boolean = true,
    ) {
        onRootsCallback = onRootsMayHaveChanged
        boundRepository = repository
        pickLocalTreeCallback = pickLocalTree
        val modeLocal = panelRoot.findViewById<RadioButton>(R.id.drawerScrapeModeLocal)
        val modeOnline = panelRoot.findViewById<RadioButton>(R.id.drawerScrapeModeOnline)
        val tmdbKeyLayout = panelRoot.findViewById<TextInputLayout>(R.id.drawerTmdbKeyLayout)
        val tmdbKeyInput = panelRoot.findViewById<TextInputEditText>(R.id.drawerTmdbApiKeyInput)
        val coverFiles = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFilesSwitch)
        val coverFrame = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFrameSwitch)
        val nfo = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeNfoSwitch)
        val filename = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeFilenameSwitch)
        val subs = panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeSubtitlesSwitch)
        val threadsSlider = panelRoot.findViewById<Slider>(R.id.drawerScrapeThreadsSlider)
        val threadsLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeThreadsLabel)
        val remoteSlider = panelRoot.findViewById<Slider>(R.id.drawerScrapeRemoteFrameSlider)
        val remoteLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeRemoteFrameLabel)
        val cacheTotalSlider = panelRoot.findViewById<Slider>(R.id.drawerRemoteCacheTotalSlider)
        val cacheTotalLabel = panelRoot.findViewById<TextView>(R.id.drawerRemoteCacheTotalLabel)
        val cachePerSlider = panelRoot.findViewById<Slider>(R.id.drawerRemoteCachePerFileSlider)
        val cachePerLabel = panelRoot.findViewById<TextView>(R.id.drawerRemoteCachePerFileLabel)
        val saveBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerSaveScrapeSettingsBtn)
        val tmdbCacheHint = panelRoot.findViewById<TextView>(R.id.drawerTmdbCacheHint)
        val clearTmdbCacheBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerClearTmdbCacheBtn)
        val subtitleLangGroup = panelRoot.findViewById<RadioGroup>(R.id.drawerSubtitleLangGroup)
        val dataBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerOpenDataBtn)
        val backupExportBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerOpenBackupExportBtn)
        val maintenanceSummary = panelRoot.findViewById<TextView>(R.id.drawerLibraryMaintenanceSummary)
        val maintenanceIssues = panelRoot.findViewById<TextView>(R.id.drawerLibraryMaintenanceIssues)
        val maintenanceRefreshBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerRefreshLibraryMaintenanceBtn)
        val maintenanceOpenBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerOpenLibraryMaintenanceBtn)
        val dirsSection = panelRoot.findViewById<View>(R.id.drawerDirsSection)
        if (!includeDirectories) {
            dirsSection?.visibility = View.GONE
        }

        fun updateCacheLabels(totalStep: Int, perStep: Int) {
            val totalMb = totalCacheMbSteps[(totalStep - 1).coerceIn(0, totalCacheMbSteps.lastIndex)]
            val perMb = perFileCacheMbSteps[(perStep - 1).coerceIn(0, perFileCacheMbSteps.lastIndex)]
            cacheTotalLabel.text = formatMbLabel(activity, totalMb, R.string.settings_remote_cache_total_fmt)
            cachePerLabel.text = formatMbLabel(activity, perMb, R.string.settings_remote_cache_per_file_fmt)
        }

        fun refreshTmdbCacheUi(
            activity: AppCompatActivity,
            hint: TextView,
            btn: MaterialButton,
            online: Boolean,
        ) {
            if (!online) {
                hint.visibility = View.GONE
                btn.visibility = View.GONE
                return
            }
            val n = TmdbDiskCache.entryCount()
            hint.visibility = View.VISIBLE
            btn.visibility = View.VISIBLE
            hint.text = activity.getString(R.string.settings_tmdb_cache_hint_fmt, n)
        }

        fun loadUi(s: ScrapeSettings) {
            val cfg = s.normalized()
            if (cfg.isOnlineMode()) modeOnline.isChecked = true else modeLocal.isChecked = true
            tmdbKeyLayout.visibility = if (cfg.isOnlineMode()) View.VISIBLE else View.GONE
            tmdbKeyInput.setText(cfg.tmdbApiKey)
            coverFiles.isChecked = cfg.coverFromFiles
            coverFrame.isChecked = cfg.coverFromVideoFrame
            nfo.isChecked = cfg.metadataFromNfo
            filename.isChecked = cfg.metadataFromFilename
            subs.isChecked = cfg.scanSidecarSubtitles
            threadsSlider.value = cfg.threadCount.toFloat()
            threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, cfg.threadCount)
            remoteSlider.value = cfg.remoteFrameConcurrency.toFloat()
            remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, cfg.remoteFrameConcurrency)
            val totalMb = (cfg.remoteCacheMaxTotalBytes / (1024 * 1024)).toInt()
            val perMb = (cfg.remoteCacheMaxBytesPerFile / (1024 * 1024)).toInt()
            cacheTotalSlider.value = mbToTotalStep(totalMb)
            cachePerSlider.value = mbToPerFileStep(perMb)
            updateCacheLabels(cacheTotalSlider.value.toInt(), cachePerSlider.value.toInt())
            refreshTmdbCacheUi(activity, tmdbCacheHint, clearTmdbCacheBtn, cfg.isOnlineMode())
            bindSubtitleLangRadio(subtitleLangGroup, SubtitlePrefs.getPrimary(activity))
            renderMaintenance(activity, maintenanceSummary, maintenanceIssues, repository.diagnostics.value)
        }

        fun handleRootsMayHaveChanged() {
            onRootsMayHaveChanged()
            activity.lifecycleScope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.refreshDiagnostics(probeSources = false)
                }
                renderMaintenance(activity, maintenanceSummary, maintenanceIssues, snapshot)
            }
        }

        subtitleLangGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = when (checkedId) {
                R.id.drawerSubtitleLangHant -> SubtitlePrefs.PrimaryLang.HANT_FIRST
                R.id.drawerSubtitleLangEn -> SubtitlePrefs.PrimaryLang.EN_FIRST
                R.id.drawerSubtitleLangNeutral -> SubtitlePrefs.PrimaryLang.NEUTRAL
                else -> SubtitlePrefs.PrimaryLang.HANS_FIRST
            }
            SubtitlePrefs.setPrimary(activity, lang)
        }

        loadUi(ScrapeConfig.readSettings(activity))

        panelRoot.findViewById<RadioGroup>(R.id.drawerScrapeModeGroup)
            .setOnCheckedChangeListener { _, checkedId ->
                val online = checkedId == R.id.drawerScrapeModeOnline
                tmdbKeyLayout.visibility = if (online) View.VISIBLE else View.GONE
                refreshTmdbCacheUi(activity, tmdbCacheHint, clearTmdbCacheBtn, online)
            }

        clearTmdbCacheBtn.setOnClickListener {
            TmdbClient.clearCache()
            refreshTmdbCacheUi(activity, tmdbCacheHint, clearTmdbCacheBtn, modeOnline.isChecked)
            Toast.makeText(activity, R.string.settings_tmdb_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        threadsSlider.addOnChangeListener { _, value, _ ->
            threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, value.toInt())
        }
        remoteSlider.addOnChangeListener { _, value, _ ->
            remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, value.toInt())
        }
        cacheTotalSlider.addOnChangeListener { _, value, _ ->
            updateCacheLabels(value.toInt(), cachePerSlider.value.toInt())
        }
        cachePerSlider.addOnChangeListener { _, value, _ ->
            updateCacheLabels(cacheTotalSlider.value.toInt(), value.toInt())
        }

        saveBtn.setOnClickListener {
            val mode = if (modeOnline.isChecked) ScrapeConfig.MODE_ONLINE else ScrapeConfig.MODE_LOCAL
            val next = ScrapeSettings(
                scrapeMode = mode,
                threadCount = threadsSlider.value.toInt(),
                remoteFrameConcurrency = remoteSlider.value.toInt(),
                coverFromFiles = coverFiles.isChecked,
                coverFromVideoFrame = coverFrame.isChecked,
                metadataFromNfo = nfo.isChecked,
                metadataFromFilename = filename.isChecked,
                scanSidecarSubtitles = subs.isChecked,
                remoteCacheMaxTotalBytes = totalStepToBytes(cacheTotalSlider.value.toInt()),
                remoteCacheMaxBytesPerFile = perFileStepToBytes(cachePerSlider.value.toInt()),
                tmdbApiKey = tmdbKeyInput.text?.toString().orEmpty(),
            ).normalized()
            ScrapeConfig.writeSettings(activity, next)
            loadUi(next)
            Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        if (includeDirectories) {
            if (directoriesPanel == null) {
                directoriesPanel = ScrapeDirectoriesPanelController(
                    activity,
                    dirsSection,
                    ::handleRootsMayHaveChanged,
                    pickLocalTree,
                ).also { it.bind() }
            } else {
                directoriesPanel?.rebindPanelRoot(dirsSection, ::handleRootsMayHaveChanged, pickLocalTree)
            }
        }

        maintenanceRefreshBtn.setOnClickListener {
            maintenanceRefreshBtn.isEnabled = false
            activity.lifecycleScope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    repository.refreshDiagnostics(probeSources = true)
                }
                renderMaintenance(activity, maintenanceSummary, maintenanceIssues, snapshot)
                maintenanceRefreshBtn.isEnabled = true
                Toast.makeText(activity, R.string.library_maintenance_refreshed, Toast.LENGTH_SHORT).show()
            }
        }

        maintenanceOpenBtn.setOnClickListener {
            LibraryMaintenanceDialog.show(
                activity = activity,
                repository = repository,
                onChanged = {
                    val snapshot = repository.diagnostics.value
                    renderMaintenance(activity, maintenanceSummary, maintenanceIssues, snapshot)
                    onRootsMayHaveChanged()
                },
                onCredentialRepairRequested = repair@ { remoteIds ->
                    val ids = remoteIds.filter { it.isNotBlank() }.distinct()
                    if (ids.isEmpty()) {
                        return@repair false
                    }
                    panelRoot.post {
                        (activity as? MainActivity)?.openScrapeDrawer()
                            ?: drawer?.openDrawer(GravityCompat.END, false)
                        val opened = promptCredentialCompletion(ids)
                        if (!opened) {
                            Toast.makeText(activity, R.string.library_issue_missing_remote_credential_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                    true
                },
            )
        }

        dataBtn.setOnClickListener {
            drawer?.closeDrawer(GravityCompat.END, false)
            DataStorageDialog.show(activity, repository) { handleRootsMayHaveChanged() }
        }

        backupExportBtn.setOnClickListener {
            drawer?.closeDrawer(GravityCompat.END, false)
            BackupExportDialog.show(
                activity = activity,
                repository = repository,
                onArchiveReady = { archive ->
                    (activity as? MainActivity)?.saveExportArchive(archive)
                },
                onImportRequested = {
                    (activity as? MainActivity)?.openBackupImportDocument()
                },
                onRollbackManageRequested = {
                    (activity as? MainActivity)?.showBackupRollbackSnapshots()
                },
            )
        }
    }

    fun reloadDirectories(activity: AppCompatActivity, panelRoot: View) {
        directoriesPanel?.reload()
    }

    fun promptCredentialCompletion(remoteIds: List<String>): Boolean =
        directoriesPanel?.promptCredentialCompletion(remoteIds) == true

    fun reloadOptions(activity: AppCompatActivity, panelRoot: View) {
        val modeLocal = panelRoot.findViewById<RadioButton>(R.id.drawerScrapeModeLocal)
        val modeOnline = panelRoot.findViewById<RadioButton>(R.id.drawerScrapeModeOnline)
        val tmdbKeyLayout = panelRoot.findViewById<TextInputLayout>(R.id.drawerTmdbKeyLayout)
        val tmdbKeyInput = panelRoot.findViewById<TextInputEditText>(R.id.drawerTmdbApiKeyInput)
        val threadsLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeThreadsLabel)
        val remoteLabel = panelRoot.findViewById<TextView>(R.id.drawerScrapeRemoteFrameLabel)
        val cacheTotalLabel = panelRoot.findViewById<TextView>(R.id.drawerRemoteCacheTotalLabel)
        val cachePerLabel = panelRoot.findViewById<TextView>(R.id.drawerRemoteCachePerFileLabel)
        val cfg = ScrapeConfig.readSettings(activity).normalized()
        if (cfg.isOnlineMode()) modeOnline.isChecked = true else modeLocal.isChecked = true
        tmdbKeyLayout.visibility = if (cfg.isOnlineMode()) View.VISIBLE else View.GONE
        tmdbKeyInput.setText(cfg.tmdbApiKey)
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFilesSwitch).isChecked = cfg.coverFromFiles
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeCoverFrameSwitch).isChecked = cfg.coverFromVideoFrame
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeNfoSwitch).isChecked = cfg.metadataFromNfo
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeFilenameSwitch).isChecked = cfg.metadataFromFilename
        panelRoot.findViewById<SwitchCompat>(R.id.drawerScrapeSubtitlesSwitch).isChecked = cfg.scanSidecarSubtitles
        panelRoot.findViewById<Slider>(R.id.drawerScrapeThreadsSlider).value = cfg.threadCount.toFloat()
        threadsLabel.text = activity.getString(R.string.settings_scrape_threads_fmt, cfg.threadCount)
        panelRoot.findViewById<Slider>(R.id.drawerScrapeRemoteFrameSlider).value = cfg.remoteFrameConcurrency.toFloat()
        remoteLabel.text = activity.getString(R.string.settings_scrape_remote_frame_fmt, cfg.remoteFrameConcurrency)
        val totalMb = (cfg.remoteCacheMaxTotalBytes / (1024 * 1024)).toInt()
        val perMb = (cfg.remoteCacheMaxBytesPerFile / (1024 * 1024)).toInt()
        panelRoot.findViewById<Slider>(R.id.drawerRemoteCacheTotalSlider).value = mbToTotalStep(totalMb)
        panelRoot.findViewById<Slider>(R.id.drawerRemoteCachePerFileSlider).value = mbToPerFileStep(perMb)
        val tStep = panelRoot.findViewById<Slider>(R.id.drawerRemoteCacheTotalSlider).value.toInt()
        val pStep = panelRoot.findViewById<Slider>(R.id.drawerRemoteCachePerFileSlider).value.toInt()
        val totalMb2 = totalCacheMbSteps[(tStep - 1).coerceIn(0, totalCacheMbSteps.lastIndex)]
        val perMb2 = perFileCacheMbSteps[(pStep - 1).coerceIn(0, perFileCacheMbSteps.lastIndex)]
        cacheTotalLabel.text = formatMbLabel(activity, totalMb2, R.string.settings_remote_cache_total_fmt)
        cachePerLabel.text = formatMbLabel(activity, perMb2, R.string.settings_remote_cache_per_file_fmt)
        val tmdbCacheHint = panelRoot.findViewById<TextView>(R.id.drawerTmdbCacheHint)
        val clearTmdbCacheBtn = panelRoot.findViewById<MaterialButton>(R.id.drawerClearTmdbCacheBtn)
        if (cfg.isOnlineMode()) {
            tmdbCacheHint.visibility = View.VISIBLE
            clearTmdbCacheBtn.visibility = View.VISIBLE
            val n = TmdbDiskCache.entryCount()
            tmdbCacheHint.text = activity.getString(R.string.settings_tmdb_cache_hint_fmt, n)
        } else {
            tmdbCacheHint.visibility = View.GONE
            clearTmdbCacheBtn.visibility = View.GONE
        }
        panelRoot.findViewById<RadioGroup>(R.id.drawerSubtitleLangGroup)?.let {
            bindSubtitleLangRadio(it, SubtitlePrefs.getPrimary(activity))
        }
        renderMaintenance(
            activity,
            panelRoot.findViewById(R.id.drawerLibraryMaintenanceSummary),
            panelRoot.findViewById(R.id.drawerLibraryMaintenanceIssues),
            boundRepository?.diagnostics?.value ?: LibraryDiagnosticsSnapshot.EMPTY,
        )
    }

    private fun renderMaintenance(
        activity: AppCompatActivity,
        summary: TextView,
        issues: TextView,
        snapshot: LibraryDiagnosticsSnapshot,
    ) {
        summary.text = activity.getString(
            R.string.library_maintenance_summary_fmt,
            snapshot.itemCount,
            snapshot.localCount,
            snapshot.remoteCount,
            snapshot.totalIssues,
        )
        val issueText = if (snapshot.issueCounts.isEmpty()) {
            activity.getString(R.string.library_maintenance_no_issues)
        } else {
            snapshot.issueCounts.entries
                .sortedByDescending { it.value }
                .joinToString("\n") { (kind, count) ->
                    activity.getString(
                        R.string.library_maintenance_issue_line_fmt,
                        issueLabel(activity, kind),
                        count,
                    )
                }
        }
        val sourceText = sourceHealthText(activity, snapshot.sourceHealth)
        issues.text = buildString {
            append(issueText)
            append('\n')
            append(sourceText)
            append('\n')
            append(RemoteDiagnosticsText.capabilityText(activity, snapshot.remoteCapabilities))
            append('\n')
            append(activity.getString(R.string.library_maintenance_scanned_fmt, snapshot.scannedAt))
        }
    }

    private fun issueLabel(activity: AppCompatActivity, kind: String): String {
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
        return if (resId != 0) activity.getString(resId) else kind
    }

    private fun sourceHealthText(activity: AppCompatActivity, sources: List<SourceHealth>): String {
        val ok = sources.count { it.reachable == true }
        val bad = sources.count { it.reachable == false }
        val unchecked = sources.count { it.reachable == null }
        val summary = activity.getString(R.string.source_health_summary_fmt, sources.size, ok, bad, unchecked)
        val badLines = sources
            .filter { it.reachable == false }
            .take(3)
            .joinToString("\n") { source ->
                activity.getString(
                    R.string.source_health_issue_fmt,
                    source.name.ifBlank { source.sourceId },
                    source.lastErrorMessage.ifBlank { source.lastErrorKind.ifBlank { "不可达" } },
                )
            }
        return if (badLines.isBlank()) summary else summary + "\n" + badLines
    }
}
