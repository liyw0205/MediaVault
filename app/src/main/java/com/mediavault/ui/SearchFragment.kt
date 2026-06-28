package com.mediavault.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {
    private lateinit var adapter: VideoCardAdapter
    private lateinit var listPager: ListPagerBar
    private var searchJob: Job? = null
    private var lastQueryForTags: String? = null
    private var lastHits: List<MediaItem> = emptyList()
    private var currentQuery: String = ""
    private val progressStore by lazy { PlaybackProgressStore(requireContext()) }

    companion object {
        private const val ARG_QUERY = "q"
        private const val STATE_PAGE = "search_page"
        fun newInstance(query: String) = SearchFragment().apply {
            arguments = Bundle().apply { putString(ARG_QUERY, query) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::listPager.isInitialized) outState.putInt(STATE_PAGE, listPager.page)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listPager = ListPagerBar(view)
        listPager.bindHost(this)
        savedInstanceState?.getInt(STATE_PAGE)?.let { listPager.restorePage(it) }
        listPager.setOnPageChanged { submitSearchPage(view) }

        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(20)
        adapter = VideoCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
            progressStore = progressStore,
        )
        grid.adapter = adapter

        val input = view.findViewById<TextInputEditText>(R.id.searchInput)
        view.findViewById<MaterialButton>(R.id.clearSearchBtn).setOnClickListener {
            input.text?.clear()
            runSearch(view, "")
        }
        view.findViewById<MaterialButton>(R.id.searchGoBtn).setOnClickListener {
            runSearch(view, input.text?.toString().orEmpty())
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(view, input.text?.toString().orEmpty())
                true
            } else false
        }

        // 进入即展示标签云；如有 ARG_QUERY 立即跑搜索，UI 先到位
        val initial = arguments?.getString(ARG_QUERY).orEmpty()
        if (initial.isNotBlank()) input.setText(initial)
        showInitialTagsOrRun(view, initial)
    }

    private fun showInitialTagsOrRun(view: View, query: String) {
        val act = activity as? MainActivity ?: return
        val all = act.repository.library.value.items
        val tagGroup = view.findViewById<ChipGroup>(R.id.matchedTags)
        val countTv = view.findViewById<TextView>(R.id.searchCount)
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        // 标签面板立即可见，不卡跳转
        bindTagChips(view, tagGroup, LibraryUi.allTags(all))
        tagGroup.visibility = View.VISIBLE
        if (query.isBlank()) {
            grid.visibility = View.GONE
            listPager.resetPage()
            listPager.update(0, enabled = false)
            adapter.submitList(emptyList())
            countTv.text = getString(R.string.search_tags_only_hint, LibraryUi.allTags(all).size)
            currentQuery = ""
            lastQueryForTags = ""
            lastHits = emptyList()
        } else {
            runSearch(view, query)
        }
    }

    private fun runSearch(view: View, query: String) {
        searchJob?.cancel()
        currentQuery = query
        val act = activity as? MainActivity ?: return
        val all = act.repository.library.value.items
        val tagGroup = view.findViewById<ChipGroup>(R.id.matchedTags)
        val countTv = view.findViewById<TextView>(R.id.searchCount)
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        val progress = view.findViewById<ProgressBar>(R.id.searchProgress)

        if (query.isBlank()) {
            progress.visibility = View.GONE
            grid.visibility = View.GONE
            listPager.resetPage()
            listPager.update(0, enabled = false)
            adapter.submitList(emptyList())
            countTv.text = getString(R.string.search_tags_only_hint, LibraryUi.allTags(all).size)
            bindTagChips(view, tagGroup, LibraryUi.allTags(all))
            lastQueryForTags = ""
            lastHits = emptyList()
            return
        }

        // 立即显示：清空列表 → 显示 loading + 标签先用同步轻量计算（仅按 contains）
        progress.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        listPager.resetPage()
        lastHits = emptyList()
        adapter.submitList(emptyList())
        countTv.text = getString(R.string.search_running_fmt, 0)
        bindTagChips(view, tagGroup, LibraryUi.matchedTags(all, query))
        lastQueryForTags = query

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                LibraryUi.searchStreaming(
                    items = all,
                    query = query,
                    batch = 24,
                    isCancelled = { currentQuery != query || !isAdded },
                ) { hits, finished ->
                    val snapshot = hits
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (currentQuery != query || !isAdded) return@launch
                        lastHits = snapshot
                        if (finished) {
                            progress.visibility = View.GONE
                            countTv.text = getString(R.string.search_count, snapshot.size)
                        } else {
                            countTv.text = getString(R.string.search_running_fmt, snapshot.size)
                        }
                        submitSearchPage(view)
                    }
                }
            }
        }
    }

    private fun submitSearchPage(view: View) {
        listPager.update(lastHits.size, enabled = lastHits.isNotEmpty())
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        adapter.submitList(listPager.slice(lastHits)) {
            // 流式追加时若已不在第一页，避免每批都滚回顶部
            if (listPager.page == 0) grid.scrollToPosition(0)
        }
    }

    private fun bindTagChips(view: View, tagGroup: ChipGroup, tags: List<String>) {
        tagGroup.removeAllViews()
        for (t in tags) {
            val chip = Chip(requireContext(), null, R.style.Widget_MediaVault_Chip_Tag)
            chip.text = t
            chip.isClickable = true
            chip.setOnClickListener {
                view.findViewById<TextInputEditText>(R.id.searchInput)?.setText(t)
                runSearch(view, t)
            }
            tagGroup.addView(chip)
        }
    }

    private fun openDetail(item: MediaItem) {
        startActivity(VideoDetailActivity.intent(requireContext(), item.path))
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshProgressHints()
    }

    fun refreshFromParent() {
        val v = view ?: return
        val input = v.findViewById<TextInputEditText>(R.id.searchInput)
        runSearch(v, input.text?.toString().orEmpty())
    }
}
