package com.mediavault.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
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
            text = item.plot.ifBlank { getString(R.string.no_plot) }
            visibility = if (item.plot.isBlank()) View.GONE else View.VISIBLE
        }

        val collectionChip = findViewById<Chip>(R.id.chipCollection)
        val collTitle = group?.title ?: item.collection.ifBlank { item.collectionKey() }
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
            val chip = Chip(this)
            chip.text = t
            chip.isClickable = true
            chip.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_SEARCH_TAG, t)
                })
            }
            tagGroup.addView(chip)
        }

        val coverPath = item.coverLocalPath()
        val coverView = findViewById<android.widget.ImageView>(R.id.detailCover)
        if (coverPath != null && File(coverPath).isFile) {
            coverView.setImageBitmap(BitmapFactory.decodeFile(coverPath))
        }

        findViewById<View>(R.id.btnPlay).setOnClickListener { play(item) }
        findViewById<View>(R.id.detailCover).setOnClickListener { play(item) }

        val related = findViewById<RecyclerView>(R.id.relatedRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 3 else 2
        related.layoutManager = GridLayoutManager(this, span)
        val adapter = VideoCardAdapter(
            onClick = { play(it) },
            onLongClick = { openDetail(it.path) },
        )
        related.adapter = adapter
        adapter.submitList(group?.items ?: listOf(item))
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    private fun openDetail(path: String) {
        startActivity(intent(this, path))
    }

    companion object {
        private const val EXTRA_PATH = "path"

        fun intent(ctx: Context, path: String): Intent =
            Intent(ctx, VideoDetailActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}