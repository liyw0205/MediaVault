package com.mediavault.ui

import android.content.Intent
import android.os.Bundle
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
import com.mediavault.scrape.ScrapePhase
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
                scrapeManager.state.collect { s ->
                    val banner = findViewById<TextView>(R.id.scrapeBanner)
                    if (s.phase == ScrapePhase.RUNNING) {
                        banner.visibility = View.VISIBLE
                        banner.text = s.message.ifBlank { getString(R.string.scrape_running_banner) }
                    } else {
                        banner.visibility = View.GONE
                    }
                }
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
                        Toast.makeText(this@MainActivity, e.message ?: "重读失败", Toast.LENGTH_LONG).show()
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
            append("媒体库 JSON：")
            append(LibraryUi.formatBytes(d.libraryBytes))
            append(" · ")
            append(d.videoCount)
            append(" 条\n")
            append("封面缓存：")
            append(LibraryUi.formatBytes(d.coverBytes))
            append(" · ")
            append(d.coverCount)
            append(" 张\n")
            append("刮削记录：")
            append(LibraryUi.formatBytes(d.scrapeRecordBytes))
            append("\n")
            append("远程点播缓存：")
            append(LibraryUi.formatBytes(d.remoteStreamBytes))
            append(" · ")
            append(d.remoteStreamFiles)
            append(" 个文件")
        }
        val actions = arrayOf(
            "清空封面缓存",
            "清空刮削记录",
            "清空远程点播缓存",
            "清空媒体库 JSON",
        )
        MvDialog.builder(this)
            .setTitle(R.string.data_title)
            .setMessage(msg)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> confirmDataAction("清空全部封面文件？") {
                        val n = repository.clearCovers()
                        Toast.makeText(this, "已删 $n 个封面", Toast.LENGTH_SHORT).show()
                    }
                    1 -> confirmDataAction("清空全部刮削记录？") {
                        repository.clearScrapeRecord()
                        Toast.makeText(this, "刮削记录已清空", Toast.LENGTH_SHORT).show()
                    }
                    2 -> confirmDataAction("清空远程边下边播前缀缓存？") {
                        val n = repository.clearRemoteStreamCache()
                        Toast.makeText(this, "已删 $n 个缓存文件", Toast.LENGTH_SHORT).show()
                    }
                    3 -> confirmDataAction("清空媒体库 JSON（保留目录与远程配置）？") {
                        lifecycleScope.launch {
                            repository.clearLibraryJson()
                                .onSuccess {
                                    Toast.makeText(this@MainActivity, "媒体库已清空", Toast.LENGTH_SHORT).show()
                                    refreshHome(recommendPathsOnly = false)
                                }
                                .onFailure { e ->
                                    Toast.makeText(this@MainActivity, e.message ?: "失败", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDataAction(message: String, onOk: () -> Unit) {
        MvDialog.builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}