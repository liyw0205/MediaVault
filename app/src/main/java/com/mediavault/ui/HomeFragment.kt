package com.mediavault.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private lateinit var adapter: VideoCardAdapter
    private var homeFilter = "recommend"
    private var page = 1
    private var lastChipRootsKey: String? = null
    /** 工具栏「重读」后为 true，在推荐 Tab 下重建持久化列表 */
    var pendingRecommendRebuild = false

    private val historyStore by lazy { HistoryStore(requireContext()) }
    private val progressStore by lazy { PlaybackProgressStore(requireContext()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            homeFilter = it.getString(STATE_FILTER) ?: homeFilter
            page = it.getInt(STATE_PAGE, page)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, homeFilter)
        outState.putInt(STATE_PAGE, page)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val grid = view.findViewById<RecyclerView>(R.id.gridRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(16)
        adapter = VideoCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
            progressStore = progressStore,
        )
        grid.adapter = adapter

        view.findViewById<MaterialButton>(R.id.prevPageBtn).setOnClickListener { changePage(-1) }
        view.findViewById<MaterialButton>(R.id.nextPageBtn).setOnClickListener { changePage(1) }
        view.findViewById<MaterialButton>(R.id.homePagerActionBtn).setOnClickListener { onPagerAction() }
        view.findViewById<TextView>(R.id.pageInfo).setOnClickListener { showPageJumpDialog() }

        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.updatedAt.collectLatest { t ->
                view.findViewById<TextView>(R.id.statTime).text = t
            }
        }
        // 库变更由 MainActivity 统一 refreshHome，避免与 collect 双通道重复刷 UI
        refreshFromParent()
    }

    fun refreshFromParent() {
        view?.let { v ->
            val act = activity as? MainActivity ?: return
            bindLibrary(v, act.repository.library.value.items)
        }
    }

    private fun bindLibrary(view: View, items: List<MediaItem>) {
        view.findViewById<TextView>(R.id.statCollections).text =
            LibraryUi.distinctCollections(items).toString()
        view.findViewById<TextView>(R.id.statItems).text = items.size.toString()
        view.findViewById<TextView>(R.id.statRoots).text = LibraryUi.distinctRoots(items).size.toString()

        rebuildFilterChipsIfNeeded(view, items)
        val list = currentList(items)
        val slice = displaySlice(list)
        adapter.submitList(slice)
        view.findViewById<TextView>(R.id.emptyText).visibility =
            if (slice.isEmpty()) View.VISIBLE else View.GONE

        val prev = view.findViewById<MaterialButton>(R.id.prevPageBtn)
        val next = view.findViewById<MaterialButton>(R.id.nextPageBtn)
        val pageInfo = view.findViewById<TextView>(R.id.pageInfo)
        val actionBtn = view.findViewById<MaterialButton>(R.id.homePagerActionBtn)
        val pager = view.findViewById<View>(R.id.homePager)

        when (homeFilter) {
            "recommend" -> {
                pager.visibility = View.VISIBLE
                prev.visibility = View.GONE
                next.visibility = View.GONE
                pageInfo.isClickable = false
                pageInfo.text = getString(R.string.recommend_count_fmt, slice.size)
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.refresh_recommend)
                view.findViewById<TextView>(R.id.statusText).text = getString(R.string.recommend_hint)
            }
            "history" -> {
                val pages = pagesFor(list)
                pager.visibility = View.VISIBLE
                prev.visibility = View.VISIBLE
                next.visibility = View.VISIBLE
                pageInfo.isClickable = true
                pageInfo.text = getString(R.string.page_fmt, page, pages.coerceAtLeast(1))
                prev.isEnabled = page > 1
                next.isEnabled = page < pages.coerceAtLeast(1)
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.clear_history)
                view.findViewById<TextView>(R.id.statusText).text = getString(R.string.items_count, list.size)
            }
            else -> {
                val pages = pagesFor(list)
                pager.visibility = View.VISIBLE
                prev.visibility = View.VISIBLE
                next.visibility = View.VISIBLE
                pageInfo.isClickable = true
                pageInfo.text = getString(R.string.page_fmt, page, pages.coerceAtLeast(1))
                prev.isEnabled = page > 1
                next.isEnabled = page < pages.coerceAtLeast(1)
                actionBtn.visibility = View.GONE
                view.findViewById<TextView>(R.id.statusText).text = getString(R.string.items_count, list.size)
            }
        }
    }

    private fun displaySlice(list: List<MediaItem>): List<MediaItem> {
        if (homeFilter == "recommend") {
            return list.take(HomeRecommendState.RECOMMEND_COUNT)
        }
        return paginate(list, page)
    }

    private fun pagesFor(list: List<MediaItem>): Int =
        (list.size + LibraryUi.PAGE_SIZE - 1) / LibraryUi.PAGE_SIZE

    private fun selectedRoot(): String? = when {
        homeFilter.startsWith("root:") -> homeFilter.removePrefix("root:")
        else -> null
    }

    private fun scopedItems(all: List<MediaItem>): List<MediaItem> =
        LibraryUi.filterByRoot(all, selectedRoot())

    private fun recommendList(filtered: List<MediaItem>): List<MediaItem> {
        val ctx = requireContext()
        HomeRecommendState.ensureLoaded(ctx)
        if (pendingRecommendRebuild) {
            pendingRecommendRebuild = false
            return HomeRecommendState.rebuildAndPersist(ctx, filtered)
        }
        if (!HomeRecommendState.hasPersistedList()) {
            if (filtered.isNotEmpty() && HomeRecommendState.shouldAutoSeedOnce(ctx)) {
                HomeRecommendState.markAutoSeeded(ctx)
                return HomeRecommendState.rebuildAndPersist(ctx, filtered)
            }
            return emptyList()
        }
        return HomeRecommendState.resolveItems(ctx, filtered)
    }

    private fun rebuildFilterChipsIfNeeded(view: View, items: List<MediaItem>) {
        val roots = LibraryUi.distinctRoots(items)
        val key = roots.joinToString("|")
        if (key == lastChipRootsKey && view.findViewById<ChipGroup>(R.id.homeFilterChips).childCount > 0) {
            return
        }
        lastChipRootsKey = key
        rebuildFilterChips(view, items)
    }

    private fun rebuildFilterChips(view: View, items: List<MediaItem>) {
        val group = view.findViewById<ChipGroup>(R.id.homeFilterChips)
        group.isSingleSelection = true
        group.removeAllViews()
        val ctx = requireContext()

        fun addChip(label: String, filterId: String) {
            val chip = Chip(ctx)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = homeFilter == filterId
            chip.setOnClickListener {
                if (homeFilter == filterId) return@setOnClickListener
                homeFilter = filterId
                page = 1
                bindLibrary(view, items)
            }
            group.addView(chip)
        }

        addChip(getString(R.string.recommend), "recommend")
        addChip(getString(R.string.history), "history")
        addChip(getString(R.string.filter_all), "all")
        for (r in LibraryUi.distinctRoots(items)) addChip(r, "root:$r")
    }

    private fun currentList(all: List<MediaItem>): List<MediaItem> {
        val filtered = scopedItems(all)
        return when (homeFilter) {
            "history" -> LibraryUi.historyItems(all, historyStore.list())
            "recommend" -> recommendList(filtered)
            else -> filtered.sortedBy { it.displayTitle().lowercase() }
        }
    }

    private fun paginate(list: List<MediaItem>, page: Int): List<MediaItem> {
        val start = (page - 1) * LibraryUi.PAGE_SIZE
        if (start >= list.size) return emptyList()
        return list.subList(start, minOf(start + LibraryUi.PAGE_SIZE, list.size))
    }

    private fun totalPages(): Int {
        val all = (activity as? MainActivity)?.repository?.library?.value?.items ?: return 1
        val list = currentList(all)
        return pagesFor(list).coerceAtLeast(1)
    }

    private fun changePage(delta: Int) {
        if (homeFilter == "recommend") return
        val v = view ?: return
        val pages = totalPages()
        page = (page + delta).coerceIn(1, pages)
        bindLibrary(v, (activity as MainActivity).repository.library.value.items)
    }

    private fun showPageJumpDialog() {
        if (homeFilter == "recommend") return
        val pages = totalPages()
        if (pages <= 1) return
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(page.toString())
            setSelection(text?.length ?: 0)
        }
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.mv_text))
        input.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.mv_muted))
        MvDialog.showStyled(
            MvDialog.builder(requireContext())
                .setTitle(R.string.page_jump_title)
                .setMessage(getString(R.string.page_jump_hint, pages))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                    page = n.coerceIn(1, pages)
                    refreshFromParent()
                }
                .setNegativeButton(android.R.string.cancel, null),
            inputRoot = input,
        )
    }

    private fun onPagerAction() {
        when (homeFilter) {
            "history" -> historyStore.clear()
            "recommend" -> {
                HomeRecommendState.clearForManualRefresh(requireContext())
                pendingRecommendRebuild = true
                page = 1
            }
        }
        refreshFromParent()
    }

    fun setHomeMode(mode: String) {
        homeFilter = when (mode) {
            "history" -> "history"
            else -> "recommend"
        }
        page = 1
        refreshFromParent()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshProgressHints()
    }

    private fun openDetail(item: MediaItem) {
        startActivity(VideoDetailActivity.intent(requireContext(), item.path))
    }

    companion object {
        private const val STATE_FILTER = "home_filter"
        private const val STATE_PAGE = "home_page"
    }
}