package com.mediavault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem

class CollectionDetailActivity : AppCompatActivity() {
    private val historyStore by lazy { HistoryStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_detail)

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val repo = (application as MediaVaultApp).repository
        val group = LibraryUi.collectionGroups(repo.library.value.items).find { it.key == key }
            ?: run { finish(); return }

        findViewById<MaterialToolbar>(R.id.collectionToolbar).apply {
            title = group.title
            setNavigationOnClickListener { finish() }
        }

        val grid = findViewById<RecyclerView>(R.id.collectionGrid)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(this, span)
        val adapter = VideoCardAdapter(
            onClick = { play(it) },
            onLongClick = { startActivity(VideoDetailActivity.intent(this, it.path)) },
        )
        grid.adapter = adapter
        adapter.submitList(group.items)
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    companion object {
        private const val EXTRA_KEY = "key"

        fun intent(ctx: Context, collectionKey: String): Intent =
            Intent(ctx, CollectionDetailActivity::class.java).putExtra(EXTRA_KEY, collectionKey)
    }
}