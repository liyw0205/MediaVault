package com.mediavault.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem
import com.mediavault.data.TmdbMatchHeuristics
import com.mediavault.data.WatchQueueStore
import com.mediavault.playback.PlaylistBuilder
import kotlinx.coroutines.launch

class VideoDetailActivity : AppCompatActivity() {
    private val historyStore by lazy { HistoryStore(this) }
    private val queueStore by lazy { WatchQueueStore(this) }

    private var relatedAll: List<MediaItem> = emptyList()
    private var relatedPage = 1
    private var relatedAdapter: MediaRowAdapter? = null
    private var collectionGroupKey: String? = null
    private var collectionGroupTitle: String? = null
    private var currentItem: MediaItem? = null

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val path = result.data
            ?.getStringExtra(PlayerActivity.EXTRA_RESULT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult
        if (path == currentItem?.path) return@registerForActivityResult
        val repo = (application as MediaVaultApp).repository
        if (repo.library.value.items.any { it.path == path }) {
            switchTo(path, scrollToTop = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_detail)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        if (savedInstanceState != null) {
            relatedPage = savedInstanceState.getInt(STATE_RELATED_PAGE, 1).coerceAtLeast(1)
        }
        bind(path)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_RELATED_PAGE, relatedPage)
    }

    private fun bind(path: String) {
        val repo = (application as MediaVaultApp).repository
        val all = repo.library.value.items
        val item = all.find { it.path == path } ?: run {
            finish()
            return
        }
        currentItem = item

        val sameCollection = LibraryUi.itemsInSameCollection(all, item)
        val key = PlaylistBuilder.collectionKey(item)
        collectionGroupKey = key
        collectionGroupTitle = LibraryUi.collectionDisplayTitle(item, key)

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        toolbar.title = item.displayTitle()
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.detailTitle).text = item.displayTitle()
        findViewById<TextView>(R.id.detailPlot).apply {
            if (item.plot.isBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                PlotText.bindPlot(this, item.plot, getString(R.string.no_plot))
            }
        }

        val collectionChip = findViewById<Chip>(R.id.chipCollection)
        val collTitle = collectionGroupTitle.orEmpty()
        collectionChip.text = collTitle
        collectionChip.setOnClickListener {
            val k = collectionGroupKey
            if (!k.isNullOrBlank() && sameCollection.size > 1) {
                startActivity(CollectionDetailActivity.intent(this, k))
            }
        }

        val ep = item.episodeLabel()
        findViewById<Chip>(R.id.chipEpisode).apply {
            text = ep.ifBlank { item.year }
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val tagGroup = findViewById<ChipGroup>(R.id.detailTags)
        tagGroup.removeAllViews()
        for (t in (item.tags + item.genres).distinct()) {
            val chip = Chip(this, null, R.style.Widget_MediaVault_Chip_Tag)
            chip.text = t
            chip.isClickable = true
            chip.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_SEARCH_TAG, t)
                })
            }
            tagGroup.addView(chip)
        }

        bindNfoFields(item)
        bindTmdbMatchRow(item)

        val coverPath = item.coverLocalPath()
        val coverView = findViewById<android.widget.ImageView>(R.id.detailCover)
        val dm = resources.displayMetrics
        val cw = (160 * dm.density).toInt().coerceAtLeast(120)
        val ch = (240 * dm.density).toInt().coerceAtLeast(180)
        CoverThumbnailLoader.load(lifecycleScope, coverView, coverPath, cw, ch)

        findViewById<View>(R.id.btnPlay).setOnClickListener { play(item) }
        bindQueueButton(item)
        coverView.setOnClickListener { play(item) }

        bindNextUp(item, sameCollection)
        bindRelatedList(path, sameCollection)
    }

    private fun bindNextUp(current: MediaItem, sameCollection: List<MediaItem>) {
        val section = findViewById<View>(R.id.nextUpSection)
        val target = resolveNextUp(current, sameCollection)
        if (target == null) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        findViewById<TextView>(R.id.nextUpSectionTitle).setText(
            if (target.mode == PlaybackPrefs.AutoplayMode.REPEAT_ONE) {
                R.string.next_up_repeat_section
            } else {
                R.string.next_up_section
            },
        )
        findViewById<TextView>(R.id.nextUpTitle).text = target.item.displayTitle()
        findViewById<TextView>(R.id.nextUpMeta).text = nextUpMeta(target, sameCollection.size)
        findViewById<MaterialButton>(R.id.btnNextUpPlay).setOnClickListener { play(target.item) }
        findViewById<MaterialButton>(R.id.btnNextUpDetail).apply {
            isEnabled = target.item.path != current.path
            setOnClickListener { switchTo(target.item.path) }
        }
    }

    private fun nextUpMeta(target: NextUpTarget, total: Int): String {
        val modeLabel = PlaybackPrefs.label(this, target.mode)
        val ep = target.item.episodeLabel()
        return if (ep.isBlank()) {
            getString(R.string.next_up_meta_fmt, modeLabel, target.position, total)
        } else {
            getString(R.string.next_up_meta_episode_fmt, modeLabel, target.position, total, ep)
        }
    }

    private fun resolveNextUp(current: MediaItem, sameCollection: List<MediaItem>): NextUpTarget? {
        if (sameCollection.size <= 1) return null
        val currentIndex = sameCollection.indexOfFirst { it.path == current.path }
        if (currentIndex < 0) return null
        val mode = PlaybackPrefs.getAutoplayMode(this)
        val targetIndex = when (mode) {
            PlaybackPrefs.AutoplayMode.REPEAT_ONE -> currentIndex
            PlaybackPrefs.AutoplayMode.LOOP_COLLECTION -> (currentIndex + 1) % sameCollection.size
            PlaybackPrefs.AutoplayMode.SEQUENTIAL,
            PlaybackPrefs.AutoplayMode.OFF -> {
                val next = currentIndex + 1
                if (next >= sameCollection.size) return null
                next
            }
        }
        return NextUpTarget(
            item = sameCollection[targetIndex],
            position = targetIndex + 1,
            mode = mode,
        )
    }

    private fun bindRelatedList(currentPath: String, sameCollection: List<MediaItem>) {
        val related = findViewById<RecyclerView>(R.id.relatedRecycler)
        val pager = findViewById<View>(R.id.relatedPager)
        val prev = findViewById<MaterialButton>(R.id.relatedPrevPageBtn)
        val next = findViewById<MaterialButton>(R.id.relatedNextPageBtn)
        val pageInfo = findViewById<TextView>(R.id.relatedPageInfo)
        val sectionTitle = findViewById<TextView>(R.id.relatedSectionTitle)

        if (relatedAdapter == null) {
            related.layoutManager = LinearLayoutManager(this)
            related.setHasFixedSize(true)
            related.setItemViewCacheSize(12)
            relatedAdapter = MediaRowAdapter(
                scope = lifecycleScope,
                onCoverClick = { switchTo(it.path) },
                onInfoClick = { switchTo(it.path) },
                queueContains = { queueStore.contains(it.path) },
                onQueueClick = { toggleQueue(it) },
            )
            related.adapter = relatedAdapter
            prev.setOnClickListener { changeRelatedPage(-1) }
            next.setOnClickListener { changeRelatedPage(1) }
        }

        relatedAll = sameCollection
        val pageSize = LibraryUi.RELATED_PAGE_SIZE
        val pages = LibraryUi.pageCount(relatedAll.size, pageSize)
        relatedPage = relatedPage.coerceIn(1, pages)

        if (relatedAll.size <= 1) {
            pager.visibility = View.GONE
            sectionTitle.visibility = if (relatedAll.size == 1) View.VISIBLE else View.GONE
            related.visibility = if (relatedAll.size == 1) View.VISIBLE else View.GONE
            relatedAdapter?.submitList(relatedAll)
            return
        }

        sectionTitle.visibility = View.VISIBLE
        related.visibility = View.VISIBLE
        pager.visibility = View.VISIBLE
        pageInfo.text = getString(
            R.string.related_page_fmt,
            relatedPage,
            pages,
            relatedAll.size,
        )
        prev.isEnabled = relatedPage > 1
        next.isEnabled = relatedPage < pages

        val slice = LibraryUi.paginateItems(relatedAll, relatedPage, pageSize)
        relatedAdapter?.submitList(slice)

        ensureCurrentInRelatedPage(currentPath, pageSize)
    }

    /** 切换条目后若当前视频不在本页，自动跳到所在页 */
    private fun ensureCurrentInRelatedPage(currentPath: String, pageSize: Int) {
        val idx = relatedAll.indexOfFirst { it.path == currentPath }
        if (idx < 0) return
        val wantPage = idx / pageSize + 1
        if (wantPage != relatedPage) {
            relatedPage = wantPage
            val pages = LibraryUi.pageCount(relatedAll.size, pageSize)
            findViewById<TextView>(R.id.relatedPageInfo).text = getString(
                R.string.related_page_fmt,
                relatedPage,
                pages,
                relatedAll.size,
            )
            findViewById<MaterialButton>(R.id.relatedPrevPageBtn).isEnabled = relatedPage > 1
            findViewById<MaterialButton>(R.id.relatedNextPageBtn).isEnabled = relatedPage < pages
            relatedAdapter?.submitList(LibraryUi.paginateItems(relatedAll, relatedPage, pageSize))
        }
    }

    private fun changeRelatedPage(delta: Int) {
        val pageSize = LibraryUi.RELATED_PAGE_SIZE
        val pages = LibraryUi.pageCount(relatedAll.size, pageSize)
        relatedPage = (relatedPage + delta).coerceIn(1, pages)
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        bindRelatedList(path, relatedAll)
    }

    private fun bindTmdbMatchRow(item: MediaItem) {
        val row = findViewById<View>(R.id.tmdbMatchRow) ?: return
        val summary = findViewById<TextView>(R.id.tmdbMatchSummary)
        val rematchBtn = findViewById<MaterialButton>(R.id.tmdbRematchBtn)
        val tmdbId = item.raw.optInt("tmdb_id", 0)
        val tmdbTitle = item.raw.optString("tmdb_match_title", "").trim()
        val tmdbYear = item.raw.optString("tmdb_match_year", "").trim()
        val confidence = item.raw.optString("tmdb_match_confidence", "").trim()
        val hasMatch = tmdbId > 0 || tmdbTitle.isNotBlank()

        if (!hasMatch) {
            // 显示但仅给出重新匹配入口
            row.visibility = View.VISIBLE
            summary.text = "TMDB · 未匹配"
            summary.alpha = 0.7f
        } else {
            row.visibility = View.VISIBLE
            val titleYear = if (tmdbYear.isNotBlank()) {
                getString(R.string.tmdb_match_year_fmt, tmdbTitle.ifBlank { "?" }, tmdbYear)
            } else tmdbTitle
            val base = getString(R.string.tmdb_match_summary_fmt, titleYear)
            val weak = TmdbMatchHeuristics.isWeakTmdbMatch(item)
            summary.text = if (weak) base + getString(R.string.tmdb_match_weak_suffix) else base
            summary.alpha = if (weak) 0.7f else 1.0f
        }
        rematchBtn.setOnClickListener {
            TmdbRematchDialog.show(this, item) { onRematchDone(item.path) }
        }
    }

    private fun onRematchDone(path: String) {
        val repo = (application as MediaVaultApp).repository
        lifecycleScope.launch {
            repo.reload()
            val refreshed = repo.library.value.items.find { it.path == path } ?: return@launch
            bindTmdbMatchRow(refreshed)
            findViewById<TextView>(R.id.detailTitle).text = refreshed.displayTitle()
            findViewById<TextView>(R.id.detailPlot).apply {
                if (refreshed.plot.isBlank()) visibility = View.GONE
                else {
                    visibility = View.VISIBLE
                    PlotText.bindPlot(this, refreshed.plot, getString(R.string.no_plot))
                }
            }
            bindNfoFields(refreshed)
        }
    }

    private fun bindNfoFields(item: MediaItem) {
        val grid = findViewById<android.view.ViewGroup>(R.id.nfoInfoGrid) ?: return
        grid.removeAllViews()
        val rows = linkedMapOf<String, String>()
        fun put(label: String, key: String) {
            val v = item.raw.optString(key, "").trim()
            if (v.isNotBlank()) rows[label] = v
        }
        put("原标题", "originaltitle")
        put("日文名", "title_jp")
        put("罗马音", "title_rm")
        put("年份", "year")
        put("首播", "premiered")
        put("上映", "releasedate")
        put("制片", "studio")
        put("国家", "country")
        put("导演", "director")
        put("编剧", "writer")
        put("分级", "mpaa")
        put("评分", "rating")
        put("时长", "runtime")
        put("状态", "status")
        put("标语", "tagline")
        if (rows.isEmpty()) {
            findViewById<View>(R.id.nfoInfoSection)?.visibility = View.GONE
            return
        }
        findViewById<View>(R.id.nfoInfoSection)?.visibility = View.VISIBLE
        for ((label, value) in rows) {
            val row = layoutInflater.inflate(R.layout.item_nfo_row, grid, false)
            row.findViewById<TextView>(R.id.nfoLabel).text = label
            row.findViewById<TextView>(R.id.nfoValue).text = value
            grid.addView(row)
        }
    }

    private fun switchTo(path: String, scrollToTop: Boolean = true) {
        intent.putExtra(EXTRA_PATH, path)
        bind(path)
        if (scrollToTop) {
            findViewById<ScrollView>(R.id.detailScroll)?.scrollTo(0, 0)
        }
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        playerLauncher.launch(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    private fun bindQueueButton(item: MediaItem) {
        findViewById<MaterialButton>(R.id.btnWatchQueue).apply {
            setText(if (queueStore.contains(item.path)) R.string.watch_queue_remove else R.string.watch_queue_add)
            setOnClickListener { toggleQueue(item) }
        }
    }

    private fun toggleQueue(item: MediaItem) {
        val added = queueStore.toggle(item.path)
        Toast.makeText(
            this,
            if (added) R.string.watch_queue_added else R.string.watch_queue_removed,
            Toast.LENGTH_SHORT,
        ).show()
        currentItem?.let { current ->
            if (current.path == item.path) bindQueueButton(current)
        }
        relatedAdapter?.refreshQueueState()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val STATE_RELATED_PAGE = "related_page"

        fun intent(ctx: Context, path: String): Intent =
            Intent(ctx, VideoDetailActivity::class.java).putExtra(EXTRA_PATH, path)
    }

    private data class NextUpTarget(
        val item: MediaItem,
        val position: Int,
        val mode: PlaybackPrefs.AutoplayMode,
    )
}
