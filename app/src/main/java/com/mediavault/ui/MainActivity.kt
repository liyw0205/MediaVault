package com.mediavault.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    }

    val repository
        get() = (application as MediaVaultApp).repository
    val scrapeManager
        get() = (application as MediaVaultApp).scrapeManager

    private val importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            repository.importFromUri(uri)
                .onSuccess { n ->
                    Toast.makeText(this@MainActivity, "已导入 $n 条", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, e.message ?: "导入失败", Toast.LENGTH_LONG).show()
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.inflateMenu(R.menu.main_top)
        toolbar.setOnMenuItemClickListener { onTopMenu(it) }

        val bottom = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(HomeFragment(), getString(R.string.tab_home))
                R.id.nav_search -> showFragment(SearchFragment(), getString(R.string.tab_search))
                R.id.nav_collections -> showFragment(CollectionsFragment(), getString(R.string.tab_collections))
                R.id.nav_scrape -> showFragment(ScrapeFragment(), getString(R.string.tab_scrape))
                else -> false
            }
        }

        if (savedInstanceState == null) {
            val tag = intent.getStringExtra(EXTRA_SEARCH_TAG)
            if (!tag.isNullOrBlank()) {
                bottom.selectedItemId = R.id.nav_search
                showFragment(SearchFragment.newInstance(tag), getString(R.string.tab_search))
            } else {
                bottom.selectedItemId = R.id.nav_home
                showFragment(HomeFragment(), getString(R.string.tab_home))
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
                    refreshHome()
                    refreshSearch()
                }
            }
        }
    }

    private fun onTopMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            lifecycleScope.launch {
                repository.reload()
                    .onSuccess { n ->
                        Toast.makeText(this@MainActivity, getString(R.string.items_count, n), Toast.LENGTH_SHORT).show()
                        refreshHome()
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
        R.id.action_import -> {
            importJson.launch(arrayOf("application/json", "text/*", "*/*"))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> false
    }

    private fun showFragment(fragment: Fragment, title: String): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        findViewById<MaterialToolbar>(R.id.toolbar).title = title
        return true
    }

    fun refreshHome() {
        val f = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (f is HomeFragment) f.refreshFromParent()
    }

    private fun refreshSearch() {
        val f = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (f is SearchFragment) f.refreshFromParent()
    }

    fun playItem(item: MediaItem) {
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    private fun showDataDialog() {
        val d = repository.dataSizes()
        val libLine = "媒体库 JSON：${LibraryUi.formatBytes(d.libraryBytes)} · ${d.videoCount} 个视频"
        val coverLine = "封面缓存：${LibraryUi.formatBytes(d.coverBytes)} · ${d.coverCount} 张"
        val scrapeLine = "刮削记录：${LibraryUi.formatBytes(d.scrapeRecordBytes)}"
        AlertDialog.Builder(this)
            .setTitle(R.string.data_title)
            .setMessage("$libLine\n$coverLine\n$scrapeLine")
            .setPositiveButton("清空封面") { _, _ ->
                val n = repository.clearCovers()
                Toast.makeText(this, "已删 $n 个封面文件", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空刮削记录") { _, _ ->
                repository.clearScrapeRecord()
                Toast.makeText(this, "刮削记录已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}