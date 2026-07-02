package com.mediavault.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.Gravity
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.BackupImportManager
import com.mediavault.data.BackupImportPrecheck
import com.mediavault.data.BackupImportResult
import com.mediavault.data.BackupMissingRemoteCredential
import com.mediavault.data.BackupRollbackSnapshot
import com.mediavault.data.BackupRollbackSnapshotPreview
import com.mediavault.data.ExportArchive
import com.mediavault.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SEARCH_TAG = "search_tag"
        private const val TAG_HOME = "tab_home"
        private const val TAG_SEARCH = "tab_search"
        private const val TAG_COLLECTIONS = "tab_collections"
        private const val TAG_SCRAPE = "tab_scrape"
    }

    val repository
        get() = (application as MediaVaultApp).repository
    val scrapeManager
        get() = (application as MediaVaultApp).scrapeManager

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerPanel: View
    private var currentTabTag: String = TAG_HOME
    private var bottomNav: BottomNavigationView? = null
    private var navRail: NavigationRailView? = null
    /** 防止 setSelectedItemId 再次触发 OnItemSelectedListener 导致栈溢出。 */
    private var syncNavReentrant = false
    /** 当前主壳是否为横屏侧栏布局（与 [layout-land] 一致）。 */
    private var shellOrientation = Configuration.ORIENTATION_UNDEFINED
    /** 刮削侧栏目录面板是否已在 Activity 生命周期内创建（旋转重载视图时不可再 register）。 */
    private var scrapeDirectoriesPanelCreated = false
    /** 下一次 wire 主壳时恢复 Tab（横竖屏 setContentView 后）。 */
    private var pendingOrientationShellRestore = false
    private var pendingExportArchive: ExportArchive? = null

    private val pickLocalTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            ScrapeDrawerBinder.onLocalTreePicked(uri)
        }

    private val createZipDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val archive = pendingExportArchive ?: return@registerForActivityResult
            pendingExportArchive = null
            if (uri == null) {
                archive.file.delete()
                Toast.makeText(this, R.string.backup_export_save_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            archive.file.inputStream().use { input -> input.copyTo(out) }
                        } ?: error("无法写入导出文件")
                    }
                }
                archive.file.delete()
                result
                    .onSuccess {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.backup_export_save_done, archive.suggestedName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure { e ->
                        Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                    }
            }
        }

    private val openBackupZipDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                Toast.makeText(this, R.string.backup_import_pick_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            precheckBackupImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(MainShellLayouts.mainActivityLayout(this))
        shellOrientation = resources.configuration.orientation

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (safeCloseScrapeDrawer()) {
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.library.collect {
                    refreshHome(recommendPathsOnly = true)
                    refreshSearch()
                }
            }
        }

        wireMainActivityShell(savedInstanceState)
    }

    private fun wireMainActivityShell(savedInstanceState: Bundle?) {
        wireMainActivityViews()

        ensureAllTabFragments()

        bottomNav = findViewById(R.id.bottomNav)
        navRail = findViewById(R.id.navRail)
        val navListener = NavigationBarView.OnItemSelectedListener { item ->
            if (syncNavReentrant) return@OnItemSelectedListener true
            val currentId = bottomNav?.selectedItemId ?: navRail?.selectedItemId ?: return@OnItemSelectedListener false
            if (item.itemId == currentId) return@OnItemSelectedListener true
            val ok = when (item.itemId) {
                R.id.nav_home -> showTab(TAG_HOME, getString(R.string.tab_home))
                R.id.nav_search -> showTab(TAG_SEARCH, getString(R.string.tab_search))
                R.id.nav_collections -> showTab(TAG_COLLECTIONS, getString(R.string.tab_collections))
                R.id.nav_scrape -> showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
                else -> false
            }
            ok
        }
        bottomNav?.setOnItemSelectedListener(navListener)
        navRail?.setOnItemSelectedListener(navListener)
        applyFusionNavVisibility()
        FusionLandscapeShell.wireMainActivity(this)
        if (savedInstanceState == null) {
            if (pendingOrientationShellRestore) {
                pendingOrientationShellRestore = false
                restoreTabAfterShellRecreate()
            } else {
                val tag = intent.getStringExtra(EXTRA_SEARCH_TAG)
                if (!tag.isNullOrBlank()) {
                    syncNavSelection(R.id.nav_search)
                    showTab(TAG_SEARCH, getString(R.string.tab_search))
                } else {
                    syncNavSelection(R.id.nav_home)
                    showTab(TAG_HOME, getString(R.string.tab_home))
                }
            }
        } else {
            restoreNavTabSelection()
        }
    }

    /** 横竖屏重载主壳后：Fragment 仍附着，仅恢复当前 Tab 与顶栏菜单。 */
    private fun restoreTabAfterShellRecreate() {
        val (tag, titleRes) = when (currentTabTag) {
            TAG_SEARCH -> TAG_SEARCH to R.string.tab_search
            TAG_COLLECTIONS -> TAG_COLLECTIONS to R.string.tab_collections
            TAG_SCRAPE -> TAG_SCRAPE to R.string.tab_scrape
            else -> TAG_HOME to R.string.tab_home
        }
        val navId = when (tag) {
            TAG_SEARCH -> R.id.nav_search
            TAG_COLLECTIONS -> R.id.nav_collections
            TAG_SCRAPE -> R.id.nav_scrape
            else -> R.id.nav_home
        }
        syncNavSelection(navId)
        applyTabChrome(tag, getString(titleRes))
    }

    private fun applyFusionNavVisibility() {
        val fusion = HomeUiPrefs.useTvFusionUi(this)
        bottomNav?.isVisible = !fusion
        navRail?.isVisible = fusion
        findViewById<View>(R.id.fusionNavRailWrap)?.isVisible = fusion
    }

    private fun applyTabChrome(tag: String, title: String) {
        currentTabTag = tag
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        toolbar.menu.clear()
        val menuRes = when (tag) {
            TAG_HOME -> R.menu.main_top_home
            TAG_SCRAPE -> R.menu.main_top_scrape
            else -> R.menu.main_top_other
        }
        toolbar.inflateMenu(menuRes)
        if (tag == TAG_SCRAPE) {
            if (HomeUiPrefs.useTvFusionUi(this)) {
                toolbar.menu.removeItem(R.id.action_scrape_drawer)
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)
            }
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        }
    }

    /** 刮削设置里「添加本地目录」与侧栏抽屉共用同一 launcher。 */
    fun openScrapePickLocalTree() {
        pickLocalTreeLauncher.launch(null)
    }

    private fun wireMainActivityViews() {
        drawerLayout = findViewById(R.id.mainDrawer)
        drawerPanel = findViewById(R.id.scrapeDrawerPanel)
        layoutDrawerGravity()
        val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)

        if (!scrapeDirectoriesPanelCreated) {
            ScrapeDrawerBinder.bind(
                activity = this,
                panelRoot = drawerContent,
                repository = repository,
                onRootsMayHaveChanged = {
                    refreshHome(recommendPathsOnly = false)
                    scrapeFragment()?.refreshRootsFromOutside()
                },
                pickLocalTree = { pickLocalTreeLauncher.launch(null) },
                drawer = drawerLayout,
            )
            scrapeDirectoriesPanelCreated = true
        } else {
            ScrapeDrawerBinder.rebindViews(
                activity = this,
                drawer = drawerLayout,
                panelRoot = drawerContent,
            )
        }

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        drawerLayout.setScrimColor(getColor(R.color.mv_player_scrim_mid))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.inflateMenu(R.menu.main_top_home)
        toolbar.setOnMenuItemClickListener { onTopMenu(it) }
    }

    private fun restoreNavTabSelection() {
        when (bottomNav?.selectedItemId ?: navRail?.selectedItemId) {
            R.id.nav_search -> showTab(TAG_SEARCH, getString(R.string.tab_search))
            R.id.nav_collections -> showTab(TAG_COLLECTIONS, getString(R.string.tab_collections))
            R.id.nav_scrape -> showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
            else -> showTab(TAG_HOME, getString(R.string.tab_home))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != shellOrientation) {
            recreateMainActivityShellForOrientation(newConfig.orientation)
        } else {
            notifyFusionUiChanged()
        }
    }

    /** @deprecated 折叠按钮已移除 */
    @Deprecated("No-op", level = DeprecationLevel.HIDDEN)
    fun onFusionLandscapeChromeChanged() {
        notifyFusionUiChanged()
    }

    private fun recreateMainActivityShellForOrientation(orientation: Int) {
        val fm = supportFragmentManager
        val fragments = listOfNotNull(
            fm.findFragmentByTag(TAG_HOME),
            fm.findFragmentByTag(TAG_SEARCH),
            fm.findFragmentByTag(TAG_COLLECTIONS),
            fm.findFragmentByTag(TAG_SCRAPE),
        )
        val txDetach = fm.beginTransaction()
        for (f in fragments) txDetach.detach(f)
        txDetach.commitNowAllowingStateLoss()

        setContentView(MainShellLayouts.mainActivityLayout(this))
        shellOrientation = orientation
        pendingOrientationShellRestore = true
        wireMainActivityShell(savedInstanceState = null)

        val txAttach = fm.beginTransaction()
        for (f in fragments) txAttach.attach(f)
        txAttach.commitNowAllowingStateLoss()

        notifyFusionUiChanged()
    }

    private fun syncNavSelection(itemId: Int) {
        if (syncNavReentrant) return
        syncNavReentrant = true
        try {
            if (bottomNav?.selectedItemId != itemId) bottomNav?.selectedItemId = itemId
            if (navRail?.selectedItemId != itemId) navRail?.selectedItemId = itemId
        } finally {
            syncNavReentrant = false
        }
    }

    /** DrawerLayout 要求抽屉子 View 带 layout_gravity；部分机型在 XML 未生效时需代码补全。 */
    private fun layoutDrawerGravity() {
        val lp = drawerPanel.layoutParams
        if (lp is DrawerLayout.LayoutParams && lp.gravity == Gravity.NO_GRAVITY) {
            lp.gravity = GravityCompat.END
            drawerPanel.layoutParams = lp
        }
    }

    private fun safeCloseScrapeDrawer(): Boolean {
        val lp = drawerPanel.layoutParams as? DrawerLayout.LayoutParams ?: return false
        if (lp.gravity == Gravity.NO_GRAVITY) return false
        return try {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END, false)
                true
            } else {
                false
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun notifyFusionUiChanged() {
        reloadTabFragmentsForFusionLayout()
        homeFragment()?.onFusionUiChanged()
        (supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment)?.onFusionUiChanged()
        (supportFragmentManager.findFragmentByTag(TAG_COLLECTIONS) as? CollectionsFragment)?.onFusionUiChanged()
        (supportFragmentManager.findFragmentByTag(TAG_SCRAPE) as? ScrapeFragment)?.onFusionUiChanged()
        FusionFocusHelper.applyFusionToolbarFocus(findViewById(R.id.toolbar))
    }

    /** 横竖屏融合 layout 资源不同，需 detach/attach 重建 Fragment 视图。 */
    private fun reloadTabFragmentsForFusionLayout() {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (tag in listOf(TAG_HOME, TAG_SEARCH, TAG_COLLECTIONS, TAG_SCRAPE)) {
            val f = fm.findFragmentByTag(tag) ?: continue
            tx.detach(f).attach(f)
        }
        tx.commitNowAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        if (currentTabTag == TAG_SCRAPE && ::drawerPanel.isInitialized) {
            val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)
            ScrapeDrawerBinder.reloadOptions(this, drawerContent)
            ScrapeDrawerBinder.reloadDirectories(this, drawerContent)
            scrapeFragment()?.refreshRootsFromOutside()
        }
    }

    private fun ensureAllTabFragments() {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TAG_HOME) != null) return
        val searchTag = intent.getStringExtra(EXTRA_SEARCH_TAG)
        val home = HomeFragment()
        val searchFrag = if (!searchTag.isNullOrBlank()) SearchFragment.newInstance(searchTag) else SearchFragment()
        val collections = CollectionsFragment()
        val scrape = ScrapeFragment()
        fm.beginTransaction()
            .add(R.id.fragmentContainer, home, TAG_HOME)
            .add(R.id.fragmentContainer, searchFrag, TAG_SEARCH)
            .add(R.id.fragmentContainer, collections, TAG_COLLECTIONS)
            .add(R.id.fragmentContainer, scrape, TAG_SCRAPE)
            .hide(searchFrag)
            .hide(collections)
            .hide(scrape)
            .commitNow()
    }

    private fun showTab(tag: String, title: String): Boolean {
        safeCloseScrapeDrawer()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (t in listOf(TAG_HOME, TAG_SEARCH, TAG_COLLECTIONS, TAG_SCRAPE)) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        applyTabChrome(tag, title)
        val navItemId = when (tag) {
            TAG_HOME -> R.id.nav_home
            TAG_SEARCH -> R.id.nav_search
            TAG_COLLECTIONS -> R.id.nav_collections
            TAG_SCRAPE -> R.id.nav_scrape
            else -> return true
        }
        syncNavSelection(navItemId)
        return true
    }

    fun openScrapeDrawer() {
        if (currentTabTag != TAG_SCRAPE) return
        val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)
        ScrapeDrawerBinder.reloadOptions(this, drawerContent)
        ScrapeDrawerBinder.reloadDirectories(this, drawerContent)
        drawerLayout.openDrawer(GravityCompat.END)
    }

    fun closeScrapeDrawer(animate: Boolean = false) {
        val lp = drawerPanel.layoutParams as? DrawerLayout.LayoutParams ?: return
        if (lp.gravity == Gravity.NO_GRAVITY) return
        try {
            drawerLayout.closeDrawer(GravityCompat.END, animate)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun saveExportArchive(archive: ExportArchive) {
        pendingExportArchive?.file?.delete()
        pendingExportArchive = archive
        createZipDocumentLauncher.launch(archive.suggestedName)
    }

    fun openBackupImportDocument() {
        openBackupZipDocumentLauncher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    fun showBackupRollbackSnapshots() {
        val manager = BackupImportManager(this)
        val snapshots = manager.listRollbackSnapshots()
        if (snapshots.isEmpty()) {
            MvDialog.show(
                MvDialog.builder(this)
                    .setTitle(R.string.backup_rollback_title)
                    .setMessage(R.string.backup_rollback_empty)
                    .setPositiveButton(android.R.string.ok, null),
            )
            return
        }
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.backup_rollback_title)
                .setMessage(rollbackSnapshotsText(snapshots))
                .setPositiveButton(R.string.backup_rollback_restore) { _, _ ->
                    promptRestoreRollbackSnapshot(snapshots)
                }
                .setNeutralButton(R.string.backup_rollback_clear) { _, _ ->
                    confirmClearRollbackSnapshots()
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun precheckBackupImport(uri: Uri) {
        Toast.makeText(this, R.string.backup_import_prechecking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupImportManager(this@MainActivity).inspect(uri) }
            }
            result
                .onSuccess { precheck -> showBackupImportPrecheck(uri, precheck) }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showBackupImportPrecheck(uri: Uri, precheck: BackupImportPrecheck) {
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.backup_import_precheck_title)
                .setMessage(backupImportPrecheckText(precheck))
                .setPositiveButton(R.string.backup_import_confirm) { _, _ -> runBackupImport(uri) }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun backupImportPrecheckText(precheck: BackupImportPrecheck): String = buildString {
        append("备份时间：")
        append(precheck.createdAt)
        append('\n')
        append("备份 schema：")
        append(if (precheck.schemaVersion > 0) precheck.schemaVersion else "--")
        append('\n')
        append("来源版本：")
        append(precheck.sourceVersionName)
        if (precheck.sourceVersionCode > 0) {
            append(" / ")
            append(precheck.sourceVersionCode)
        }
        append('\n')
        append("内容条目：")
        append(precheck.contentEntryCount)
        append('\n')
        append("媒体库：")
        append(precheck.itemCount)
        append(" 条")
        append('\n')
        append("本机目录：")
        append(precheck.localRootCount)
        append(" 个")
        append('\n')
        append("远程配置：")
        append(precheck.remoteCount)
        append(" 个")
        if (precheck.redactedRemotePasswordCount > 0) {
            append('\n')
            append("远程密码：脱敏 ")
            append(precheck.redactedRemotePasswordCount)
            append(" 个，可保留当前 ")
            append(precheck.preservedRemotePasswordCount)
            append(" 个，需手动补 ")
            append(precheck.missingRemotePasswordCount)
            append(" 个")
            appendMissingRemoteCredentials(precheck.missingRemoteCredentials)
        }
        if (precheck.tmdbKeyRedacted) {
            append('\n')
            append("TMDB Key：已脱敏")
            if (precheck.tmdbKeyWillBePreserved) append("，会保留当前 Key")
        }
        if (precheck.warnings.isNotEmpty()) {
            append("\n\n注意：")
            precheck.warnings.forEach { warning ->
                append('\n')
                append("- ")
                append(warning)
            }
        }
        append("\n\n导入会覆盖当前媒体库、目录配置、远程配置、刮削记录、播放进度和基础偏好。导入前会保存内部回滚快照；如果导入失败，会自动回滚。")
    }

    private fun runBackupImport(uri: Uri) {
        Toast.makeText(this, R.string.backup_import_importing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupImportManager(this@MainActivity).importBackup(uri, repository) }
            }
            result
                .onSuccess { imported ->
                    refreshHome(recommendPathsOnly = false)
                    refreshSearch()
                    scrapeFragment()?.refreshRootsFromOutside()
                    showBackupImportResult(imported)
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showBackupImportResult(imported: BackupImportResult) {
        val needsCredentialAction = imported.missingRemoteCredentials.isNotEmpty() || imported.tmdbKeyStillMissing
        val builder = MvDialog.builder(this)
            .setTitle(R.string.backup_import_result_title)
            .setMessage(backupImportResultText(imported))
            .setNeutralButton(R.string.backup_import_recheck_sources) { _, _ ->
                refreshImportedSourceHealth()
            }
        if (needsCredentialAction) {
            builder
                .setPositiveButton(
                    if (imported.missingRemoteCredentials.isNotEmpty()) {
                        R.string.backup_import_complete_remote_credentials
                    } else {
                        R.string.backup_import_open_settings
                    },
                ) { _, _ ->
                    openImportCredentialSettings(imported)
                }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            builder
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.backup_import_open_settings) { _, _ ->
                    openScrapeSettingsFromImport()
                }
        }
        MvDialog.show(builder)
    }

    private fun backupImportResultText(imported: BackupImportResult): String = buildString {
        append(getString(R.string.backup_import_done_fmt, imported.itemCount, imported.localRootCount, imported.remoteCount))
        append('\n')
        append("刮削记录、播放进度、历史记录和基础偏好已恢复。")
        append('\n')
        append("远程密码：备份脱敏 ")
        append(imported.redactedRemotePasswords)
        append(" 个，已保留当前密码 ")
        append(imported.restoredRemotePasswords)
        append(" 个，仍需补充 ")
        append(imported.missingRemotePasswords)
        append(" 个。")
        appendMissingRemoteCredentials(imported.missingRemoteCredentials)
        append('\n')
        append("TMDB Key：")
        append(
            when {
                imported.restoredTmdbKey -> "备份已脱敏，已保留当前 Key。"
                imported.tmdbKeyStillMissing -> "备份已脱敏，当前没有可保留的 Key，需要重新填写。"
                else -> "已恢复。"
            },
        )
        append('\n')
        append("回滚快照：")
        append(imported.rollbackSnapshotPath)
        if (imported.missingRemotePasswords > 0 || imported.tmdbKeyStillMissing) {
            append("\n\n需要补充凭据：打开刮削设置后，在“管理媒体目录”编辑远程配置；TMDB Key 在“刮削”配置区填写。")
        }
    }

    private fun StringBuilder.appendMissingRemoteCredentials(remotes: List<BackupMissingRemoteCredential>) {
        if (remotes.isEmpty()) return
        append('\n')
        append("需补远程：")
        remotes.take(5).forEach { remote ->
            append('\n')
            append("- ")
            append(missingRemoteCredentialLabel(remote))
        }
        if (remotes.size > 5) {
            append('\n')
            append("- 另有 ")
            append(remotes.size - 5)
            append(" 个远程")
        }
    }

    private fun missingRemoteCredentialLabel(remote: BackupMissingRemoteCredential): String {
        val name = remote.name.ifBlank { remote.id.ifBlank { "remote" } }
        val type = remote.type.ifBlank { "remote" }.uppercase()
        val host = remote.host.ifBlank { "--" }
        return "$name ($type $host)"
    }

    private fun rollbackSnapshotsText(snapshots: List<BackupRollbackSnapshot>): String = buildString {
        append("保留 ")
        append(snapshots.size)
        append(" 个内部回滚快照。导入失败时会自动使用最新快照回滚；也可手动选择一个快照恢复。清空不会影响当前媒体库。")
        snapshots.forEachIndexed { index, snapshot ->
            append("\n\n")
            append(index + 1)
            append(". ")
            append(snapshot.createdAt)
            append('\n')
            append(snapshot.name)
            append(" · ")
            append(LibraryUi.formatBytes(snapshot.bytes))
        }
    }

    private fun promptRestoreRollbackSnapshot(snapshots: List<BackupRollbackSnapshot>) {
        val labels = snapshots.map { snapshot ->
            "${snapshot.createdAt} · ${LibraryUi.formatBytes(snapshot.bytes)}\n${snapshot.name}"
        }.toTypedArray()
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.backup_rollback_restore)
                .setItems(labels) { _, which ->
                    snapshots.getOrNull(which)?.let { inspectRollbackSnapshotBeforeRestore(it) }
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun inspectRollbackSnapshotBeforeRestore(snapshot: BackupRollbackSnapshot) {
        Toast.makeText(this, R.string.backup_rollback_inspecting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupImportManager(this@MainActivity).inspectRollbackSnapshot(snapshot.name) }
            }
            result
                .onSuccess { preview -> confirmRestoreRollbackSnapshot(preview) }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun confirmRestoreRollbackSnapshot(preview: BackupRollbackSnapshotPreview) {
        MvDialog.show(
            MvDialog.builder(this)
                .setTitle(R.string.backup_rollback_restore_preview_title)
                .setMessage(rollbackSnapshotPreviewText(preview))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    restoreRollbackSnapshot(preview.snapshot)
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun rollbackSnapshotPreviewText(preview: BackupRollbackSnapshotPreview): String = buildString {
        val snapshot = preview.snapshot
        append(getString(R.string.backup_rollback_restore_confirm_fmt, snapshot.createdAt, snapshot.name))
        append("\n\n")
        append("快照 schema：")
        append(if (preview.schemaVersion > 0) preview.schemaVersion else "--")
        append('\n')
        append("将恢复：")
        append(preview.fileEntryCount)
        append(" 个文件项、")
        append(preview.prefEntryCount)
        append(" 组偏好。")
        if (preview.missingFileEntries.isNotEmpty()) {
            append("\n\n快照未包含，将恢复为不存在：")
            preview.missingFileEntries.take(4).forEach { path ->
                append('\n')
                append("- ")
                append(rollbackEntryLabel(path))
            }
            if (preview.missingFileEntries.size > 4) {
                append('\n')
                append("- 另有 ")
                append(preview.missingFileEntries.size - 4)
                append(" 项")
            }
        }
        if (preview.warnings.isNotEmpty()) {
            append("\n\n注意：")
            preview.warnings.forEach { warning ->
                append('\n')
                append("- ")
                append(warning)
            }
        }
    }

    private fun rollbackEntryLabel(path: String): String = when (path) {
        "files/library.json" -> "媒体库"
        "files/roots.list" -> "本机目录配置"
        "files/remotes.json" -> "远程配置"
        "files/scrape-record.tsv" -> "刮削记录"
        "files/library-diagnostics.json" -> "库诊断快照"
        "files/scrape-config.json" -> "刮削设置"
        else -> path
    }

    private fun restoreRollbackSnapshot(snapshot: BackupRollbackSnapshot) {
        Toast.makeText(this, R.string.backup_rollback_restoring, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupImportManager(this@MainActivity).restoreRollbackSnapshot(snapshot.name, repository) }
            }
            result
                .onSuccess { restored ->
                    refreshHome(recommendPathsOnly = false)
                    refreshSearch()
                    scrapeFragment()?.refreshRootsFromOutside()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.backup_rollback_restored_fmt, restored.createdAt),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun openImportCredentialSettings(imported: BackupImportResult) {
        openScrapeSettingsFromImport()
        val remoteIds = imported.missingRemoteCredentials.map { it.id }
        if (remoteIds.isNotEmpty()) {
            val opened = ScrapeDrawerBinder.promptCredentialCompletion(remoteIds)
            if (!opened) {
                Toast.makeText(this, R.string.backup_import_remote_credentials_unavailable, Toast.LENGTH_LONG).show()
            }
        } else if (imported.tmdbKeyStillMissing) {
            Toast.makeText(this, R.string.backup_import_fill_tmdb_key_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun openScrapeSettingsFromImport() {
        syncNavSelection(R.id.nav_scrape)
        showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
        openScrapeDrawer()
    }

    private fun refreshImportedSourceHealth() {
        Toast.makeText(this, R.string.backup_import_rechecking_sources, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                repository.refreshDiagnostics(probeSources = true)
            }
            refreshHome(recommendPathsOnly = false)
            refreshSearch()
            scrapeFragment()?.refreshRootsFromOutside()
            if (::drawerPanel.isInitialized) {
                val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)
                ScrapeDrawerBinder.reloadOptions(this@MainActivity, drawerContent)
            }
            val ok = snapshot.sourceHealth.count { it.reachable == true }
            val bad = snapshot.sourceHealth.count { it.reachable == false }
            val unchecked = snapshot.sourceHealth.count { it.reachable == null }
            Toast.makeText(
                this@MainActivity,
                getString(R.string.backup_import_recheck_sources_done_fmt, snapshot.sourceHealth.size, ok, bad, unchecked),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun confirmClearRollbackSnapshots() {
        MvDialog.show(
            MvDialog.builder(this)
                .setMessage(R.string.backup_rollback_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val count = BackupImportManager(this).clearRollbackSnapshots()
                    Toast.makeText(this, getString(R.string.backup_rollback_cleared_fmt, count), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun onTopMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            HomeRecommendState.clearPersist(this)
            HomeRecommendState.resetAutoSeedFlag(this)
            homeFragment()?.pendingRecommendRebuild = true
            lifecycleScope.launch {
                repository.reload()
                    .onSuccess { n ->
                        Toast.makeText(this@MainActivity, getString(R.string.items_count, n), Toast.LENGTH_SHORT).show()
                        refreshHome(recommendPathsOnly = false)
                    }
                    .onFailure { e ->
                        Toast.makeText(this@MainActivity, e.message ?: getString(R.string.reload_failed), Toast.LENGTH_LONG).show()
                    }
            }
            true
        }
        R.id.action_scrape_drawer -> {
            openScrapeDrawer()
            true
        }
        else -> false
    }

    fun refreshHome(@Suppress("UNUSED_PARAMETER") recommendPathsOnly: Boolean = false) {
        homeFragment()?.refreshFromParent()
    }

    private fun homeFragment(): HomeFragment? =
        supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment

    private fun scrapeFragment(): ScrapeFragment? =
        supportFragmentManager.findFragmentByTag(TAG_SCRAPE) as? ScrapeFragment

    private fun refreshSearch() {
        val f = supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment
        f?.refreshFromParent()
    }

    fun playItem(item: MediaItem) {
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }
}
