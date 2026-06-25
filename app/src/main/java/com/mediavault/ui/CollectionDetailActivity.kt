package com.mediavault.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
            title = "${group.title} · ${getString(R.string.collection_count, group.items.size)}"
            setNavigationOnClickListener { finish() }
        }

        val list = findViewById<RecyclerView>(R.id.collectionGrid)
        list.layoutManager = LinearLayoutManager(this)
        val adapter = MediaRowAdapter(
            onCoverClick = { play(it) },
            onInfoClick = { startActivity(VideoDetailActivity.intent(this, it.path)) },
        )
        list.adapter = adapter
        adapter.submitList(group.items)
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    companion object {
        private const val EXTRA_KEY = "collection_key"

        fun intent(ctx: android.content.Context, key: String) =
            android.content.Intent(ctx, CollectionDetailActivity::class.java).putExtra(EXTRA_KEY, key)
    }
}