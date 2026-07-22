package com.mediavault.ui

import android.content.res.Configuration
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
import com.mediavault.data.MediaItem
import kotlinx.coroutines.launch

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

    private val pickLocalTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            ScrapeDrawerBinder.onLocalTreePicked(uri)
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
            val currentId = bottomNav?.selectedItemId ?: navRail?.selectedItemId
                ?: return@OnItemSelectedListener false
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

    private fun applyTabChrome(tag: String, @Suppress("UNUSED_PARAMETER") title: String) {
        currentTabTag = tag
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        toolbar.menu.clear()
        toolbar.setOnClickListener(null)

        if (tag != TAG_SCRAPE) {
            // 主页/搜索/合集：去掉顶栏，把高度让给内容（横竖屏一致）
            toolbar.isVisible = false
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
            return
        }

        // 刮削页：不显示「刮削」标题；原主页「重读」放到标题位（左侧），可点
        toolbar.isVisible = true
        toolbar.title = getString(R.string.reload)
        toolbar.inflateMenu(R.menu.main_top_scrape)
        if (HomeUiPrefs.useTvFusionUi(this)) {
            // 横屏设置在第三栏，无需抽屉入口
            toolbar.menu.removeItem(R.id.action_scrape_drawer)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)
        }
        bindScrapeTitleAsReload(toolbar)
    }

    /** 把工具栏左侧标题「重读」做成点击入口（替代原主页菜单项）。 */
    private fun bindScrapeTitleAsReload(toolbar: MaterialToolbar) {
        val reloadLabel = getString(R.string.reload)
        toolbar.title = reloadLabel
        toolbar.post {
            if (currentTabTag != TAG_SCRAPE) return@post
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is android.widget.TextView &&
                    child.text?.toString() == reloadLabel
                ) {
                    child.isClickable = true
                    child.isFocusable = true
                    child.setOnClickListener { performLibraryReload() }
                    return@post
                }
            }
            // 找不到标题 TextView 时退化为整条 toolbar 可点
            toolbar.setOnClickListener { performLibraryReload() }
        }
    }

    private fun performLibraryReload() {
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
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: getString(R.string.reload_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
        toolbar.title = ""
        toolbar.menu.clear()
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

    private fun onTopMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            performLibraryReload()
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