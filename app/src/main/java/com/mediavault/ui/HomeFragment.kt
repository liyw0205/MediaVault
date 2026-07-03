package com.mediavault.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore
import com.mediavault.data.WatchQueueStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private lateinit var adapter: VideoCardAdapter
    private lateinit var continueAdapter: VideoCardAdapter
    private lateinit var recentAdapter: VideoCardAdapter
    private var homeFilter = "recommend"
    private var page = 1
    private var lastChipRootsKey: String? = null
    /** 工具栏「重读」后为 true，在推荐 Tab 下重建持久化列表 */
    var pendingRecommendRebuild = false

    private val historyStore by lazy { HistoryStore(requireContext()) }
    private val progressStore by lazy { PlaybackProgressStore(requireContext()) }
    private val queueStore by lazy { WatchQueueStore(requireContext()) }

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
        inflater.inflate(FusionFragmentLayouts.home(requireContext()), container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val grid = view.findViewById<RecyclerView>(R.id.gridRecycler)
        applyHomeGrid(grid)
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(16)
        adapter = VideoCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
            progressStore = progressStore,
            queueContains = { queueStore.contains(it.path) },
            onQueueClick = { toggleQueue(it) },
            showQueueAction = { homeFilter == "queue" },
        )
        grid.adapter = adapter
        continueAdapter = setupShelf(view.findViewById(R.id.continueRecycler))
        recentAdapter = setupShelf(view.findViewById(R.id.recentRecycler))

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
        FusionFocusHelper.applyFusionToolbarFocus(view)
        if (HomeUiPrefs.useTvFusionUi(requireContext())) {
            FusionLandscapeShell.applyFragmentRoot(view, FusionUiMetrics.SidebarKind.Home)
        }
    }

    private fun applyHomeGrid(grid: RecyclerView) {
        val ctx = requireContext()
        val fusion = HomeUiPrefs.useTvFusionUi(ctx)
        val span = FusionUiMetrics.gridSpanCount(ctx, FusionUiMetrics.SidebarKind.Home)
        if (grid.layoutManager !is GridLayoutManager || (grid.layoutManager as GridLayoutManager).spanCount != span) {
            grid.layoutManager = GridLayoutManager(ctx, span)
        }
        grid.descendantFocusability = if (fusion) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    private fun setupShelf(recycler: RecyclerView): VideoCardAdapter {
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(8)
        recycler.descendantFocusability =
            if (HomeUiPrefs.useTvFusionUi(requireContext())) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
        val shelfAdapter = VideoCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
            progressStore = progressStore,
            fixedCardWidthPx = resources.getDimensionPixelSize(R.dimen.home_shelf_card_width),
        )
        recycler.adapter = shelfAdapter
        return shelfAdapter
    }

    fun onFusionUiChanged() {
        view?.let { FusionLandscapeShell.applyFragmentRoot(it, FusionUiMetrics.SidebarKind.Home) }
        view?.findViewById<RecyclerView>(R.id.gridRecycler)?.let { applyHomeGrid(it) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    /** @deprecated 手动界面切换已移除 */
    fun onHomeLayoutModeChanged() = onFusionUiChanged()
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

        bindWorkflowSections(view, items)
        val list = currentList(items)
        bindLibraryTitle(view, list.size)
        rebuildFilterChipsIfNeeded(view, items)
        if (!isRecommendFilter()) {
            page = page.coerceIn(1, pagesFor(list).coerceAtLeast(1))
        }
        val slice = displaySlice(list)
        view.findViewById<TextView>(R.id.emptyText).text = emptyTextForCurrentFilter()
        adapter.submitList(slice) {
            view.findViewById<RecyclerView>(R.id.gridRecycler).scrollToPosition(0)
        }
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
                view.findViewById<TextView>(R.id.statusText).apply {
                    visibility = View.VISIBLE
                    text = HomeRecommendState.summary(requireContext())
                        .ifBlank { getString(R.string.recommend_rules_hint) }
                }
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
                bindPagedStatus(view, list.size)
            }
            "queue" -> {
                val pages = pagesFor(list)
                pager.visibility = View.VISIBLE
                prev.visibility = View.VISIBLE
                next.visibility = View.VISIBLE
                pageInfo.isClickable = true
                pageInfo.text = getString(R.string.page_fmt, page, pages.coerceAtLeast(1))
                prev.isEnabled = page > 1
                next.isEnabled = page < pages.coerceAtLeast(1)
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.watch_queue_clear)
                bindPagedStatus(view, list.size)
            }
            else -> if (isRecommendFilter()) {
                pager.visibility = View.VISIBLE
                prev.visibility = View.GONE
                next.visibility = View.GONE
                pageInfo.isClickable = false
                pageInfo.text = getString(R.string.recommend_count_fmt, slice.size)
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.refresh_recommend)
                view.findViewById<TextView>(R.id.statusText).apply {
                    visibility = View.VISIBLE
                    text = recommendReasonStatusText()
                }
            } else {
                val pages = pagesFor(list)
                pager.visibility = View.VISIBLE
                prev.visibility = View.VISIBLE
                next.visibility = View.VISIBLE
                pageInfo.isClickable = true
                pageInfo.text = getString(R.string.page_fmt, page, pages.coerceAtLeast(1))
                prev.isEnabled = page > 1
                next.isEnabled = page < pages.coerceAtLeast(1)
                actionBtn.visibility = View.GONE
                bindPagedStatus(view, list.size)
            }
        }
    }

    private fun bindPagedStatus(view: View, total: Int) {
        view.findViewById<TextView>(R.id.statusText).apply {
            visibility = View.VISIBLE
            text = pagedStatusText(total)
        }
    }

    private fun pagedStatusText(total: Int): String {
        if (total <= 0) return getString(R.string.items_count, 0)
        val start = ((page - 1) * LibraryUi.PAGE_SIZE) + 1
        val end = minOf(start + LibraryUi.PAGE_SIZE - 1, total)
        return getString(R.string.home_page_range_fmt, start, end, total)
    }

    private fun bindLibraryTitle(view: View, count: Int) {
        view.findViewById<TextView>(R.id.homeLibraryTitle).text =
            getString(R.string.home_library_current_title_fmt, currentFilterLabel(), count)
    }

    private fun currentFilterLabel(): String =
        when {
            homeFilter == "recommend" -> getString(R.string.recommend)
            homeFilter.startsWith("recommend:") -> {
                val reason = selectedRecommendReason().orEmpty()
                getString(
                    R.string.home_filter_nested_label_fmt,
                    getString(R.string.recommend),
                    LibraryUi.recommendationReasonLabel(reason),
                )
            }
            homeFilter == "watching" -> getString(R.string.home_watching)
            homeFilter == "unwatched" -> getString(R.string.home_unwatched)
            homeFilter == "queue" -> getString(R.string.watch_queue)
            homeFilter == "history" -> getString(R.string.history)
            homeFilter == "all" -> getString(R.string.filter_all)
            homeFilter.startsWith("root:") -> homeFilter.removePrefix("root:")
            else -> getString(R.string.filter_all)
        }

    private fun emptyTextForCurrentFilter(): String =
        when {
            homeFilter == "recommend" -> getString(R.string.home_recommend_empty)
            homeFilter.startsWith("recommend:") -> {
                val reason = selectedRecommendReason().orEmpty()
                getString(
                    R.string.home_recommend_reason_empty_fmt,
                    LibraryUi.recommendationReasonLabel(reason),
                )
            }
            homeFilter == "queue" -> getString(R.string.watch_queue_empty)
            homeFilter == "watching" -> getString(R.string.home_watching_empty)
            homeFilter == "unwatched" -> getString(R.string.home_unwatched_empty)
            homeFilter == "history" -> getString(R.string.home_history_empty)
            homeFilter.startsWith("root:") -> getString(
                R.string.home_root_empty_fmt,
                homeFilter.removePrefix("root:"),
            )
            else -> getString(R.string.no_items)
        }

    private fun bindWorkflowSections(view: View, items: List<MediaItem>) {
        val continueItems = continueWatchingItems(items)
        val recentItems = recentlyAddedItems(items)
        view.findViewById<TextView>(R.id.continueTitle).text =
            sectionCountTitle(R.string.home_continue_watching, continueItems.size)
        view.findViewById<TextView>(R.id.recentTitle).text =
            sectionCountTitle(R.string.home_recently_added, recentItems.size)
        bindShelf(
            continueAdapter,
            view.findViewById(R.id.continueRecycler),
            view.findViewById(R.id.continueEmpty),
            continueItems,
        )
        bindShelf(
            recentAdapter,
            view.findViewById(R.id.recentRecycler),
            view.findViewById(R.id.recentEmpty),
            recentItems,
        )
    }

    private fun bindShelf(
        shelfAdapter: VideoCardAdapter,
        recycler: RecyclerView,
        empty: TextView,
        items: List<MediaItem>,
    ) {
        shelfAdapter.submitList(items) {
            if (items.isNotEmpty()) recycler.scrollToPosition(0)
        }
        recycler.isVisible = items.isNotEmpty()
        empty.isVisible = items.isEmpty()
    }

    private fun sectionCountTitle(labelRes: Int, count: Int): String =
        getString(R.string.home_section_count_title_fmt, getString(labelRes), count)

    private fun continueWatchingItems(items: List<MediaItem>): List<MediaItem> =
        watchingItems(items).take(WORKFLOW_SHELF_COUNT)

    private fun watchingItems(items: List<MediaItem>): List<MediaItem> =
        items.mapNotNull { item ->
            progressStore.getEntry(item.path)?.let { entry -> item to entry.updatedAt }
        }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun recentlyAddedItems(items: List<MediaItem>): List<MediaItem> =
        items.sortedWith(compareByDescending<MediaItem> { it.modified }.thenBy { it.displayTitle().lowercase() })
            .take(WORKFLOW_SHELF_COUNT)

    private fun displaySlice(list: List<MediaItem>): List<MediaItem> {
        if (isRecommendFilter()) {
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
            HomeRecommendState.rebuildAndPersist(
                ctx = ctx,
                filtered = filtered,
                historyPaths = historyStore.list(),
                progressPaths = progressPaths(filtered),
            )
        }
        if (!HomeRecommendState.hasPersistedList()) {
            if (filtered.isNotEmpty() && HomeRecommendState.shouldAutoSeedOnce(ctx)) {
                HomeRecommendState.markAutoSeeded(ctx)
                HomeRecommendState.rebuildAndPersist(
                    ctx = ctx,
                    filtered = filtered,
                    historyPaths = historyStore.list(),
                    progressPaths = progressPaths(filtered),
                )
            } else {
                return emptyList()
            }
        }
        return HomeRecommendState.resolveItems(ctx, filtered, selectedRecommendReason())
    }

    private fun progressPaths(items: List<MediaItem>): Set<String> =
        items.mapNotNull { item ->
            if (progressStore.getEntry(item.path) != null) item.path else null
        }.toSet()

    private fun rebuildFilterChipsIfNeeded(view: View, items: List<MediaItem>) {
        val roots = LibraryUi.distinctRoots(items)
        val counts = homeFilterCounts(items)
        val reasonKey = HomeRecommendState.reasonCounts(requireContext(), items)
            .joinToString("|") { "${it.reason}:${it.count}" }
        val key = listOf(
            roots.joinToString("|"),
            reasonKey,
            "${counts.watching}:${counts.unwatched}:${counts.queue}:${counts.history}:${counts.all}",
            homeFilter,
        ).joinToString("#")
        if (key == lastChipRootsKey && view.findViewById<ChipGroup>(R.id.homeFilterChips).childCount > 0) {
            return
        }
        lastChipRootsKey = key
        rebuildFilterChips(view, items)
    }

    private fun rebuildFilterChips(view: View, items: List<MediaItem>) {
        val group = view.findViewById<ChipGroup>(R.id.homeFilterChips)
        val fusion = HomeUiPrefs.useTvFusionUi(requireContext())
        FusionTagLayoutHelper.applyFusionChipGroup(group, fusion)
        group.isSingleSelection = true
        group.removeAllViews()
        val ctx = requireContext()
        val counts = homeFilterCounts(items)

        fun addChip(label: String, filterId: String) {
            val chip = Chip(ctx)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = homeFilter == filterId
            FusionTagLayoutHelper.styleFilterChip(chip, fusion)
            chip.setOnClickListener {
                if (homeFilter == filterId) return@setOnClickListener
                homeFilter = filterId
                page = 1
                bindLibrary(view, items)
            }
            group.addView(chip)
        }

        addChip(getString(R.string.recommend), "recommend")
        HomeRecommendState.reasonCounts(ctx, items).forEach { reason ->
            addChip(getString(R.string.recommend_reason_chip_fmt, reason.label, reason.count), "recommend:${reason.reason}")
        }
        addChip(filterCountLabel(R.string.home_watching, counts.watching), "watching")
        addChip(filterCountLabel(R.string.home_unwatched, counts.unwatched), "unwatched")
        addChip(filterCountLabel(R.string.watch_queue, counts.queue), "queue")
        addChip(filterCountLabel(R.string.history, counts.history), "history")
        addChip(filterCountLabel(R.string.filter_all, counts.all), "all")
        for (r in LibraryUi.distinctRoots(items)) addChip(r, "root:$r")
    }

    private fun filterCountLabel(labelRes: Int, count: Int): String =
        getString(R.string.home_filter_count_chip_fmt, getString(labelRes), count)

    private fun homeFilterCounts(items: List<MediaItem>): HomeFilterCounts =
        HomeFilterCounts(
            watching = watchingItems(items).size,
            unwatched = unwatchedItems(items).size,
            queue = LibraryUi.watchQueueItems(items, queueStore.list()).size,
            history = LibraryUi.historyItems(items, historyStore.list()).size,
            all = items.size,
        )

    private fun currentList(all: List<MediaItem>): List<MediaItem> {
        val filtered = scopedItems(all)
        return when (homeFilter) {
            "history" -> LibraryUi.historyItems(all, historyStore.list())
            "queue" -> LibraryUi.watchQueueItems(all, queueStore.list())
            "watching" -> watchingItems(filtered)
            "unwatched" -> unwatchedItems(filtered)
            "recommend" -> recommendList(filtered)
            else -> if (isRecommendFilter()) recommendList(filtered) else filtered.sortedBy { it.displayTitle().lowercase() }
        }
    }

    private fun unwatchedItems(items: List<MediaItem>): List<MediaItem> {
        val history = historyStore.list().toHashSet()
        return items.asSequence()
            .filter { it.path !in history && progressStore.getEntry(it.path) == null }
            .sortedWith(compareByDescending<MediaItem> { it.modified }.thenBy { it.displayTitle().lowercase() })
            .toList()
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
        if (isRecommendFilter()) return
        val v = view ?: return
        val pages = totalPages()
        page = (page + delta).coerceIn(1, pages)
        bindLibrary(v, (activity as MainActivity).repository.library.value.items)
    }

    private fun showPageJumpDialog() {
        if (isRecommendFilter()) return
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
            "queue" -> {
                queueStore.clear()
                Toast.makeText(requireContext(), R.string.watch_queue_cleared, Toast.LENGTH_SHORT).show()
            }
            "recommend" -> {
                HomeRecommendState.clearForManualRefresh(requireContext())
                pendingRecommendRebuild = true
                page = 1
            }
            else -> if (isRecommendFilter()) {
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
        view?.findViewById<RecyclerView>(R.id.gridRecycler)?.let { applyHomeGrid(it) }
        if (::adapter.isInitialized) adapter.refreshProgressHints()
        refreshFromParent()
        if (::continueAdapter.isInitialized) continueAdapter.refreshProgressHints()
        if (::recentAdapter.isInitialized) recentAdapter.refreshProgressHints()
    }

    private fun openDetail(item: MediaItem) {
        startActivity(VideoDetailActivity.intent(requireContext(), item.path))
    }

    private fun toggleQueue(item: MediaItem) {
        val added = queueStore.toggle(item.path)
        Toast.makeText(
            requireContext(),
            if (added) R.string.watch_queue_added else R.string.watch_queue_removed,
            Toast.LENGTH_SHORT,
        ).show()
        refreshFromParent()
    }

    companion object {
        private const val STATE_FILTER = "home_filter"
        private const val STATE_PAGE = "home_page"
        private const val WORKFLOW_SHELF_COUNT = 12
    }

    private data class HomeFilterCounts(
        val watching: Int,
        val unwatched: Int,
        val queue: Int,
        val history: Int,
        val all: Int,
    )

    private fun isRecommendFilter(): Boolean =
        homeFilter == "recommend" || homeFilter.startsWith("recommend:")

    private fun selectedRecommendReason(): String? =
        homeFilter.removePrefix("recommend:").takeIf { homeFilter.startsWith("recommend:") && it.isNotBlank() }

    private fun recommendReasonStatusText(): String {
        val reason = selectedRecommendReason().orEmpty()
        val label = LibraryUi.recommendationReasonLabel(reason)
        val summary = HomeRecommendState.summary(requireContext()).ifBlank { getString(R.string.recommend_rules_hint) }
        return getString(R.string.recommend_reason_status_fmt, label, summary)
    }
}
