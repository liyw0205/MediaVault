package com.mediavault.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.mediavault.MediaVaultApp
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem

class CollectionDetailActivity : AppCompatActivity() {
    private val historyStore by lazy { HistoryStore(this) }
    private lateinit var listPager: ListPagerBar
    private lateinit var adapter: MediaRowAdapter
    private var allItems: List<MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_detail)

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val repo = (application as MediaVaultApp).repository
        val group = LibraryUi.resolveCollectionGroup(repo.library.value.items, key)
            ?: run { finish(); return }

        allItems = group.items

        findViewById<MaterialToolbar>(R.id.collectionToolbar).apply {
            title = group.title
            subtitle = getString(R.string.collection_count, group.items.size)
            setNavigationOnClickListener { finish() }
        }

        listPager = ListPagerBar(findViewById(R.id.listPager))
        savedInstanceState?.getInt(STATE_PAGE)?.let { listPager.restorePage(it) }
        listPager.setOnPageChanged { bindPage() }

        val list = findViewById<RecyclerView>(R.id.collectionGrid)
        list.layoutManager = LinearLayoutManager(this)
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)
        adapter = MediaRowAdapter(
            scope = lifecycleScope,
            onCoverClick = { startActivity(VideoDetailActivity.intent(this, it.path)) },
            onInfoClick = { startActivity(VideoDetailActivity.intent(this, it.path)) },
        )
        list.adapter = adapter

        bindPageBarJump()
        bindPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::listPager.isInitialized) outState.putInt(STATE_PAGE, listPager.page)
    }

    private fun bindPageBarJump() {
        val info = findViewById<android.widget.TextView>(R.id.listPageInfo)
        info.setOnClickListener { showPageJumpDialog() }
    }

    private fun bindPage() {
        listPager.update(allItems.size, enabled = true)
        adapter.submitList(listPager.slice(allItems)) {
            findViewById<RecyclerView>(R.id.collectionGrid).scrollToPosition(0)
        }
    }

    private fun showPageJumpDialog() {
        val pages = LibraryUi.pageCount(allItems.size, LibraryUi.PAGE_SIZE)
        if (pages <= 1) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(listPager.page.toString())
            setSelection(text?.length ?: 0)
        }
        input.setTextColor(ContextCompat.getColor(this, R.color.mv_text))
        input.setHintTextColor(ContextCompat.getColor(this, R.color.mv_muted))
        MvDialog.showStyled(
            MvDialog.builder(this)
                .setTitle(R.string.page_jump_title)
                .setMessage(getString(R.string.page_jump_hint, pages))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                    listPager.restorePage(n.coerceIn(1, pages))
                    bindPage()
                }
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = input,
        )
    }

    private fun play(item: MediaItem) {
        historyStore.add(item.path)
        startActivity(PlayerActivity.intent(this, item.path, item.displayTitle()))
    }

    companion object {
        private const val EXTRA_KEY = "collection_key"
        private const val STATE_PAGE = "collection_detail_page"

        fun intent(ctx: android.content.Context, key: String) =
            android.content.Intent(ctx, CollectionDetailActivity::class.java).putExtra(EXTRA_KEY, key)
    }
}