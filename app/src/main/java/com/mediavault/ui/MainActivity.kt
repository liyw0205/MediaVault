package com.mediavault.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        private const val TAG_HOME = "tab_home"
        private const val TAG_SEARCH = "tab_search"
        private const val TAG_COLLECTIONS = "tab_collections"
        private const val TAG_SCRAPE = "tab_scrape"
    }

    val repository
        get() = (application as MediaVaultApp).repository
    val scrapeManager
        get() = (application as MediaVaultApp).scrapeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.inflateMenu(R.menu.main_top)
        toolbar.setOnMenuItemClickListener { onTopMenu(it) }

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
                else -> false
            }
        }

        if (savedInstanceState == null) {
            val tag = intent.getStringExtra(EXTRA_SEARCH_TAG)
            if (!tag.isNullOrBlank()) {
                bottom.selectedItemId = R.id.nav_search
                showTab(TAG_SEARCH, getString(R.string.tab_search))
            } else {
                bottom.selectedItemId = R.id.nav_home
                showTab(TAG_HOME, getString(R.string.tab_home))
            }
        } else {
            when (bottom.selectedItemId) {
                R.id.nav_search -> showTab(TAG_SEARCH, getString(R.string.tab_search))
                R.id.nav_collections -> showTab(TAG_COLLECTIONS, getString(R.string.tab_collections))
                R.id.nav_scrape -> showTab(TAG_SCRAPE, getString(R.string.tab_scrape))
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

    /** 一次创建四个 Tab，之后只 hide/show，避免主页 Fragment 反复销毁重建 */
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
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (t in listOf(TAG_HOME, TAG_SEARCH, TAG_COLLECTIONS, TAG_SCRAPE)) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        findViewById<MaterialToolbar>(R.id.toolbar).title = title
        return true
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
        R.id.action_data -> {
            showDataDialog()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> false
    }

    fun refreshHome(@Suppress("UNUSED_PARAMETER") recommendPathsOnly: Boolean = false) {
        homeFragment()?.refreshFromParent()
    }

    private fun homeFragment(): HomeFragment? =
        supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment

    private fun refreshSearch() {
        val f = supportFragmentManager.findFragmentByTag(TAG_SEARCH) as? SearchFragment
        f?.refreshFromParent()
    }

    fun playItem(item: MediaItem) {
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    private fun showDataDialog() {
        val d = repository.dataSizes()
        val msg = buildString {
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
        val root = LayoutInflater.from(this).inflate(R.layout.dialog_data, null)
        root.findViewById<TextView>(R.id.dataDialogStats).text = msg
        val builder = MvDialog.builder(this)
            .setTitle(R.string.data_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
        val dialog = MvDialog.showStyled(builder)
        root.findViewById<View>(R.id.dataClearCovers).setOnClickListener {
            confirmDataAction(getString(R.string.confirm_clear_covers)) {
                val n = repository.clearCovers()
                Toast.makeText(this, getString(R.string.data_cleared_covers_fmt, n), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearScrape).setOnClickListener {
            confirmDataAction(getString(R.string.confirm_clear_scrape)) {
                repository.clearScrapeRecord()
                Toast.makeText(this, R.string.data_cleared_scrape, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearRemoteStream).setOnClickListener {
            confirmDataAction(getString(R.string.confirm_clear_remote)) {
                val n = repository.clearRemoteStreamCache()
                Toast.makeText(this, getString(R.string.data_cleared_remote_fmt, n), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.findViewById<View>(R.id.dataClearLibrary).setOnClickListener {
            confirmDataAction(getString(R.string.confirm_clear_library)) {
                lifecycleScope.launch {
                    repository.clearLibraryJson()
                        .onSuccess {
                            Toast.makeText(this@MainActivity, R.string.data_cleared_library, Toast.LENGTH_SHORT).show()
                            refreshHome(recommendPathsOnly = false)
                            dialog.dismiss()
                        }
                        .onFailure { e ->
                            Toast.makeText(this@MainActivity, e.message ?: getString(R.string.action_failed), Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
    }

    private fun confirmDataAction(message: String, onOk: () -> Unit) {
        MvDialog.builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}