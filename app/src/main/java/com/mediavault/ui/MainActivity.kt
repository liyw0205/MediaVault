package com.mediavault.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.MediaItem
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SEARCH_TAG = "search_tag"
        /** 从设置 Tab 跳转：打开刮削 Tab 并展开右侧刮削侧栏 */
        const val EXTRA_OPEN_SCRAPE_DRAWER = "open_scrape_drawer"
        private const val TAG_HOME = "tab_home"
        private const val TAG_SEARCH = "tab_search"
        private const val TAG_COLLECTIONS = "tab_collections"
        private const val TAG_SCRAPE = "tab_scrape"
        private const val TAG_SETTINGS = "tab_settings"
    }

    val repository
        get() = (application as MediaVaultApp).repository
    val scrapeManager
        get() = (application as MediaVaultApp).scrapeManager

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerPanel: View
    private var currentTabTag: String = TAG_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.mainDrawer)
        drawerPanel = findViewById(R.id.scrapeDrawerPanel)
        val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)

        ScrapeDrawerBinder.bind(
            activity = this,
            drawer = drawerLayout,
            panelRoot = drawerContent,
            repository = repository,
            onRootsMayHaveChanged = {
                refreshHome(recommendPathsOnly = false)
                scrapeFragment()?.refreshRootsFromOutside()
            },
        )

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        drawerLayout.setScrimColor(0x66000000)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.inflateMenu(R.menu.main_top_home)
        toolbar.setOnMenuItemClickListener { onTopMenu(it) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                        drawerLayout.closeDrawer(GravityCompat.END, false)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        ensureAllTabFragments()

        val bottom = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottom.setOnItemSelectedListener { item ->
            if (item.itemId == bottom.selectedItemId) {
                return@setOnItemSelectedListener true
            }
            when (item.itemId) {
                R.id.nav_home -> showTab(TAG_HOME, getString(R.string.tab_home))
                R.id.nav_search -> showTab(TAG_SEARCH, getString(R.string.tab_search))
                R.id.nav_collections -> showTab(TAG_COLLECTIONS, getString(R.string.tab_collections))
                R.id.nav_scrape -> showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
                R.id.nav_settings -> showTab(TAG_SETTINGS, getString(R.string.tab_settings))
                else -> false
            }
        }

        if (savedInstanceState == null) {
            val tag = intent.getStringExtra(EXTRA_SEARCH_TAG)
            val openScrapeDrawer = intent.getBooleanExtra(EXTRA_OPEN_SCRAPE_DRAWER, false)
            when {
                openScrapeDrawer -> {
                    bottom.selectedItemId = R.id.nav_scrape
                    showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
                    drawerContent.post { openScrapeDrawer() }
                }
                !tag.isNullOrBlank() -> {
                    bottom.selectedItemId = R.id.nav_search
                    showTab(TAG_SEARCH, getString(R.string.tab_search))
                }
                else -> {
                    bottom.selectedItemId = R.id.nav_home
                    showTab(TAG_HOME, getString(R.string.tab_home))
                }
            }
        } else {
            when (bottom.selectedItemId) {
                R.id.nav_search -> showTab(TAG_SEARCH, getString(R.string.tab_search))
                R.id.nav_collections -> showTab(TAG_COLLECTIONS, getString(R.string.tab_collections))
                R.id.nav_scrape -> showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
                R.id.nav_settings -> showTab(TAG_SETTINGS, getString(R.string.tab_settings))
                else -> showTab(TAG_HOME, getString(R.string.tab_home))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.library.collect {
                    refreshHome(recommendPathsOnly = true)
                    refreshSearch()
                }
            }
        }
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
        val settings = SettingsFragment()
        fm.beginTransaction()
            .add(R.id.fragmentContainer, home, TAG_HOME)
            .add(R.id.fragmentContainer, searchFrag, TAG_SEARCH)
            .add(R.id.fragmentContainer, collections, TAG_COLLECTIONS)
            .add(R.id.fragmentContainer, scrape, TAG_SCRAPE)
            .add(R.id.fragmentContainer, settings, TAG_SETTINGS)
            .hide(searchFrag)
            .hide(collections)
            .hide(scrape)
            .hide(settings)
            .commitNow()
    }

    private fun showTab(tag: String, title: String): Boolean {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END, false)
        }
        currentTabTag = tag
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (t in listOf(TAG_HOME, TAG_SEARCH, TAG_COLLECTIONS, TAG_SCRAPE, TAG_SETTINGS)) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        toolbar.menu.clear()
        val menuRes = when (tag) {
            TAG_HOME -> R.menu.main_top_home
            TAG_SCRAPE -> R.menu.main_top_scrape
            TAG_SETTINGS -> R.menu.main_top_other
            else -> R.menu.main_top_other
        }
        toolbar.inflateMenu(menuRes)
        if (tag == TAG_SCRAPE) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        }
        return true
    }

    fun openScrapeDrawer() {
        if (currentTabTag != TAG_SCRAPE) return
        val drawerContent = findViewById<View>(R.id.scrapeDrawerContent)
        ScrapeDrawerBinder.reloadOptions(this, drawerContent)
        ScrapeDrawerBinder.reloadDirectories(this, drawerContent)
        drawerLayout.openDrawer(GravityCompat.END)
    }

    /** 设置 Tab：刮削选项、管理目录、TMDB/字幕等均在刮削侧栏 */
    fun openScrapeSettingsFromSettingsTab() {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_scrape
        showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
        findViewById<View>(R.id.scrapeDrawerContent).post { openScrapeDrawer() }
    }

    fun closeScrapeDrawer(animate: Boolean = false) {
        drawerLayout.closeDrawer(GravityCompat.END, animate)
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