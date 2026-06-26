package com.mediavault.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem
import java.io.File

class VideoDetailActivity : AppCompatActivity() {
    private val historyStore by lazy { HistoryStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_detail)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        bind(path)
    }

    private fun bind(path: String) {
        val repo = (application as MediaVaultApp).repository
        val item = repo.library.value.items.find { it.path == path } ?: run {
            finish()
            return
        }
        val group = LibraryUi.collectionGroups(repo.library.value.items)
            .find { g -> g.items.any { it.path == path } }

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
        val collTitle = group?.title ?: LibraryUi.sanitizeCollectionName(
            item.collection.ifBlank { item.collectionKey() },
        )
        collectionChip.text = collTitle
        collectionChip.setOnClickListener {
            if (group != null) {
                startActivity(CollectionDetailActivity.intent(this, group.key))
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

        val coverPath = item.coverLocalPath()
        val coverView = findViewById<android.widget.ImageView>(R.id.detailCover)
        if (coverPath != null && File(coverPath).isFile) {
            coverView.setImageBitmap(BitmapFactory.decodeFile(coverPath))
        } else {
            coverView.setImageDrawable(null)
        }

        findViewById<View>(R.id.btnPlay).setOnClickListener { play(item) }
        coverView.setOnClickListener { play(item) }

        val related = findViewById<RecyclerView>(R.id.relatedRecycler)
        related.layoutManager = LinearLayoutManager(this)
        val adapter = MediaRowAdapter(
            onCoverClick = { switchTo(it.path) },
            onInfoClick = { switchTo(it.path) },
        )
        related.adapter = adapter
        adapter.submitList(group?.items ?: listOf(item))
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

    private fun switchTo(path: String) {
        intent.putExtra(EXTRA_PATH, path)
        bind(path)
        findViewById<NestedScrollView>(R.id.detailScroll)?.scrollTo(0, 0)
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    companion object {
        private const val EXTRA_PATH = "path"

        fun intent(ctx: Context, path: String): Intent =
            Intent(ctx, VideoDetailActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}