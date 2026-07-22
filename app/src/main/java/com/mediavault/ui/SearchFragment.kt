package com.mediavault.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.R
import com.mediavault.data.HistoryStore
import com.mediavault.data.MediaItem
import com.mediavault.data.PlaybackProgressStore
import com.mediavault.data.WatchQueueStore
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
    private var sort: SearchOptions.Sort = SearchOptions.Sort.RecentPlayed
    private var source: SearchOptions.Source = SearchOptions.Source.All
    private var typeFilter: SearchOptions.Type = SearchOptions.Type.All
    private var watchState: SearchOptions.WatchState = SearchOptions.WatchState.All
    private var filtersExpanded: Boolean = true
    private val progressStore by lazy { PlaybackProgressStore(requireContext()) }
    private val historyStore by lazy { HistoryStore(requireContext()) }
    private val queueStore by lazy { WatchQueueStore(requireContext()) }

    companion object {
        private const val ARG_QUERY = "q"
        private const val STATE_PAGE = "search_page"
        private const val STATE_QUERY = "search_q"
        private const val STATE_SORT = "search_sort"
        private const val STATE_SOURCE = "search_source"
        private const val STATE_TYPE = "search_type"
        private const val STATE_WATCH = "search_watch"
        fun newInstance(query: String) = SearchFragment().apply {
            arguments = Bundle().apply { putString(ARG_QUERY, query) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::listPager.isInitialized) outState.putInt(STATE_PAGE, listPager.page)
        outState.putString(STATE_QUERY, currentQuery)
        outState.putString(STATE_SORT, sort.name)
        outState.putString(STATE_SOURCE, source.name)
        outState.putString(STATE_TYPE, typeFilter.name)
        outState.putString(STATE_WATCH, watchState.name)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(FusionFragmentLayouts.search(requireContext()), container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listPager = ListPagerBar(view)
        listPager.bindHost(this)
        savedInstanceState?.getInt(STATE_PAGE)?.let { listPager.restorePage(it) }
        listPager.setOnPageChanged { submitSearchPage(view) }

        savedInstanceState?.let {
            sort = runCatching { SearchOptions.Sort.valueOf(it.getString(STATE_SORT) ?: "") }.getOrDefault(sort)
            source = runCatching { SearchOptions.Source.valueOf(it.getString(STATE_SOURCE) ?: "") }.getOrDefault(source)
            typeFilter = runCatching { SearchOptions.Type.valueOf(it.getString(STATE_TYPE) ?: "") }.getOrDefault(typeFilter)
            watchState = runCatching { SearchOptions.WatchState.valueOf(it.getString(STATE_WATCH) ?: "") }.getOrDefault(watchState)
        }

        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        applySearchGrid(grid)
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(20)
        adapter = VideoCardAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
            progressStore = progressStore,
            sidebarKind = FusionUiMetrics.SidebarKind.Search,
            queueContains = { queueStore.contains(it.path) },
            onQueueClick = { toggleQueue(it) },
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

        val sortBtn = view.findViewById<MaterialButton>(R.id.searchSortBtn)
        val watchBtn = view.findViewById<MaterialButton>(R.id.searchWatchBtn)
        val sourceBtn = view.findViewById<MaterialButton>(R.id.searchSourceBtn)
        val typeBtn = view.findViewById<MaterialButton>(R.id.searchTypeBtn)
        sortBtn.setOnClickListener { showSortMenu(view) }
        watchBtn.setOnClickListener { showWatchMenu(view) }
        sourceBtn.setOnClickListener { showSourceMenu(view) }
        typeBtn.setOnClickListener { showTypeMenu(view) }
        view.findViewById<MaterialButton>(R.id.searchFiltersToggle)?.setOnClickListener {
            filtersExpanded = !filtersExpanded
            applyFiltersCollapseState(view)
        }
        refreshFilterLabels(view)
        applyFiltersCollapseState(view)

        // 恢复 query 优先于 ARG_QUERY；新建时用 ARG_QUERY
        val restoredQuery = savedInstanceState?.getString(STATE_QUERY)
        val initial = restoredQuery ?: arguments?.getString(ARG_QUERY).orEmpty()
        if (initial.isNotBlank()) input.setText(initial)
        showInitialTagsOrRun(view, initial)
        FusionFocusHelper.applyFusionToolbarFocus(view)
        if (HomeUiPrefs.useTvFusionUi(requireContext())) {
            FusionLandscapeShell.applyFragmentRoot(view, FusionUiMetrics.SidebarKind.Search)
        }
    }

    private fun showInitialTagsOrRun(view: View, query: String) {
        val act = activity as? MainActivity ?: return
        val all = act.repository.library.value.items
        val tagGroup = view.findViewById<ChipGroup>(R.id.matchedTags)
        val countTv = view.findViewById<TextView>(R.id.searchCount)
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        bindTagChips(view, tagGroup, LibraryUi.allTags(all))
        tagGroup.visibility = View.VISIBLE
        resizeTagScroller(view, tagGroup)
        if (query.isBlank()) {
            grid.visibility = View.GONE
            listPager.resetPage()
            listPager.update(0, enabled = false)
            adapter.submitList(emptyList())
            countTv.text = getString(R.string.search_tags_only_hint, LibraryUi.allTags(all).size)
            currentQuery = ""
            lastQueryForTags = ""
            lastHits = emptyList()
            filtersExpanded = true
            applyFiltersCollapseState(view)
        } else {
            runSearch(view, query)
        }
    }

    private fun runSearch(view: View, query: String) {
        searchJob?.cancel()
        val prevQuery = currentQuery
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
            resizeTagScroller(view, tagGroup)
            lastQueryForTags = ""
            lastHits = emptyList()
            filtersExpanded = true
            applyFiltersCollapseState(view)
            return
        }

        // 仅在关键词变化时自动折叠；改排序/来源/类型不强制收起
        if (query != prevQuery) {
            filtersExpanded = false
        }
        applyFiltersCollapseState(view)
        progress.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        listPager.resetPage()
        lastHits = emptyList()
        adapter.submitList(emptyList())
        countTv.text = getString(R.string.search_running_fmt, 0)
        bindTagChips(view, tagGroup, LibraryUi.matchedTags(all, query))
        resizeTagScroller(view, tagGroup)
        lastQueryForTags = query

        val curSort = sort
        val curSource = source
        val curType = typeFilter
        val curWatchState = watchState
        val historyPaths = historyStore.list()
        val historySet = historyPaths.toHashSet()
        val historyRanks = historyPaths.withIndex().associate { it.value to it.index }
        val progressCache = HashMap<String, PlaybackProgressStore.Entry?>()
        fun progressEntry(item: MediaItem): PlaybackProgressStore.Entry? {
            if (!progressCache.containsKey(item.path)) {
                progressCache[item.path] = progressStore.getEntry(item.path)
            }
            return progressCache[item.path]
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                LibraryUi.searchStreaming(
                    items = all,
                    query = query,
                    batch = 24,
                    isCancelled = { currentQuery != query || !isAdded },
                ) { hits, finished ->
                    val filtered = hits.filter {
                        val progressEntry = progressEntry(it)
                        SearchOptions.matchesSource(it, curSource) &&
                            SearchOptions.matchesType(it, curType) &&
                            SearchOptions.matchesWatchState(
                                it,
                                curWatchState,
                                hasProgress = progressEntry != null,
                                inHistory = it.path in historySet,
                            )
                    }.toMutableList()
                    SearchOptions.sortInPlace(
                        filtered,
                        curSort,
                        progressUpdatedAt = { progressEntry(it)?.updatedAt },
                        historyIndex = { historyRanks[it.path] },
                    )
                    val snapshot: List<MediaItem> = filtered
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
            if (listPager.page == 0) grid.scrollToPosition(0)
        }
    }

    private fun showSortMenu(view: View) {
        val labels = arrayOf(
            getString(R.string.search_sort_recent_played),
            getString(R.string.search_sort_modified),
            getString(R.string.search_sort_title_az),
            getString(R.string.search_sort_year_desc),
        )
        val values = arrayOf(
            SearchOptions.Sort.RecentPlayed,
            SearchOptions.Sort.Modified,
            SearchOptions.Sort.TitleAZ,
            SearchOptions.Sort.YearDesc,
        )
        val checked = values.indexOf(sort).coerceAtLeast(0)
        MvDialog.show(
            MvDialog.builder(requireContext())
            .setTitle(R.string.search_sort_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                sort = values[which]
                d.dismiss()
                refreshFilterLabels(view)
                runSearchKeepQuery(view)
            },
        )
    }

    private fun showWatchMenu(view: View) {
        val labels = arrayOf(
            getString(R.string.search_watch_all),
            getString(R.string.search_watch_unwatched),
            getString(R.string.search_watch_watching),
            getString(R.string.search_watch_watched),
        )
        val values = arrayOf(
            SearchOptions.WatchState.All,
            SearchOptions.WatchState.Unwatched,
            SearchOptions.WatchState.Watching,
            SearchOptions.WatchState.Watched,
        )
        val checked = values.indexOf(watchState).coerceAtLeast(0)
        MvDialog.show(
            MvDialog.builder(requireContext())
            .setTitle(R.string.search_watch_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                watchState = values[which]
                d.dismiss()
                refreshFilterLabels(view)
                runSearchKeepQuery(view)
            },
        )
    }

    private fun showSourceMenu(view: View) {
        val labels = arrayOf(
            getString(R.string.search_source_all),
            getString(R.string.search_source_local),
            getString(R.string.search_source_remote),
        )
        val values = arrayOf(
            SearchOptions.Source.All,
            SearchOptions.Source.Local,
            SearchOptions.Source.Remote,
        )
        val checked = values.indexOf(source).coerceAtLeast(0)
        MvDialog.show(
            MvDialog.builder(requireContext())
            .setTitle(R.string.search_source_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                source = values[which]
                d.dismiss()
                refreshFilterLabels(view)
                runSearchKeepQuery(view)
            },
        )
    }

    private fun showTypeMenu(view: View) {
        val labels = arrayOf(
            getString(R.string.search_type_all),
            getString(R.string.search_type_tv),
            getString(R.string.search_type_movie),
        )
        val values = arrayOf(
            SearchOptions.Type.All,
            SearchOptions.Type.Tv,
            SearchOptions.Type.Movie,
        )
        val checked = values.indexOf(typeFilter).coerceAtLeast(0)
        MvDialog.show(
            MvDialog.builder(requireContext())
            .setTitle(R.string.search_type_title)
            .setSingleChoiceItems(labels, checked) { d, which ->
                typeFilter = values[which]
                d.dismiss()
                refreshFilterLabels(view)
                runSearchKeepQuery(view)
            },
        )
    }

    private fun runSearchKeepQuery(view: View) {
        val input = view.findViewById<TextInputEditText>(R.id.searchInput)
        val q = input.text?.toString().orEmpty()
        runSearch(view, q)
    }

    private fun refreshFilterLabels(view: View) {
        val sortLabel = when (sort) {
            SearchOptions.Sort.RecentPlayed -> getString(R.string.search_sort_recent_played)
            SearchOptions.Sort.Modified -> getString(R.string.search_sort_modified)
            SearchOptions.Sort.TitleAZ -> getString(R.string.search_sort_title_az)
            SearchOptions.Sort.YearDesc -> getString(R.string.search_sort_year_desc)
        }
        val sourceLabel = when (source) {
            SearchOptions.Source.All -> getString(R.string.search_source_all)
            SearchOptions.Source.Local -> getString(R.string.search_source_local)
            SearchOptions.Source.Remote -> getString(R.string.search_source_remote)
        }
        val typeLabel = when (typeFilter) {
            SearchOptions.Type.All -> getString(R.string.search_type_all)
            SearchOptions.Type.Tv -> getString(R.string.search_type_tv)
            SearchOptions.Type.Movie -> getString(R.string.search_type_movie)
        }
        val watchLabel = when (watchState) {
            SearchOptions.WatchState.All -> getString(R.string.search_watch_all)
            SearchOptions.WatchState.Unwatched -> getString(R.string.search_watch_unwatched)
            SearchOptions.WatchState.Watching -> getString(R.string.search_watch_watching)
            SearchOptions.WatchState.Watched -> getString(R.string.search_watch_watched)
        }
        view.findViewById<MaterialButton>(R.id.searchSortBtn).text = "排序 · $sortLabel"
        view.findViewById<MaterialButton>(R.id.searchWatchBtn).text = "状态 · $watchLabel"
        view.findViewById<MaterialButton>(R.id.searchSourceBtn).text = "来源 · $sourceLabel"
        view.findViewById<MaterialButton>(R.id.searchTypeBtn).text = "类型 · $typeLabel"
        applyFiltersCollapseState(view)
    }

    private fun applyFiltersCollapseState(view: View) {
        val row = view.findViewById<View>(R.id.searchFiltersRow) ?: return
        val toggle = view.findViewById<MaterialButton>(R.id.searchFiltersToggle)
        val hasQuery = currentQuery.isNotBlank()
        // 无关键词时展开筛选；有关键词后默认折叠
        val showFilters = !hasQuery || filtersExpanded
        row.isVisible = showFilters
        if (toggle != null) {
            toggle.isVisible = hasQuery
            toggle.text = if (filtersExpanded) {
                getString(R.string.search_filters_collapse)
            } else {
                getString(R.string.search_filters_expand)
            }
        }
    }

    private fun bindTagChips(view: View, tagGroup: ChipGroup, tags: List<String>) {
        val fusion = HomeUiPrefs.useTvFusionUi(requireContext())
        FusionTagLayoutHelper.applyFusionChipGroup(tagGroup, fusion)
        tagGroup.removeAllViews()
        for (t in tags) {
            val chip = Chip(requireContext(), null, R.style.Widget_MediaVault_Chip_Tag)
            chip.text = t
            chip.isClickable = true
            FusionTagLayoutHelper.styleTagChip(chip, fusion)
            chip.setOnClickListener {
                view.findViewById<TextInputEditText>(R.id.searchInput)?.setText(t)
                runSearch(view, t)
            }
            tagGroup.addView(chip)
        }
        resizeTagScroller(view, tagGroup)
    }

    private fun resizeTagScroller(view: View, tagGroup: ChipGroup) {
        val scroll = view.findViewById<ScrollView>(R.id.searchTagsScroll) ?: return
        scroll.visibility = if (tagGroup.childCount == 0) View.GONE else View.VISIBLE
        if (tagGroup.childCount == 0) return
        val gridVisible = view.findViewById<RecyclerView>(R.id.searchRecycler)?.isVisible == true
        scroll.post {
            val rootHeight = view.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val maxHeight = (rootHeight * if (gridVisible) 0.22f else 0.58f).toInt()
                .coerceAtLeast(if (gridVisible) 96.dp() else 220.dp())
            val contentHeight = tagGroup.measuredHeight + scroll.paddingTop + scroll.paddingBottom
            val target = contentHeight.coerceAtMost(maxHeight).coerceAtLeast(0)
            val lp = scroll.layoutParams
            if (target > 0 && lp.height != target) {
                lp.height = target
                scroll.layoutParams = lp
            }
        }
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
        adapter.refreshQueueState()
    }

    private fun applySearchGrid(grid: RecyclerView) {
        val ctx = requireContext()
        val fusion = HomeUiPrefs.useTvFusionUi(ctx)
        val span = FusionUiMetrics.gridSpanCount(ctx, FusionUiMetrics.SidebarKind.Search)
        if (grid.layoutManager !is GridLayoutManager || (grid.layoutManager as GridLayoutManager).spanCount != span) {
            grid.layoutManager = GridLayoutManager(ctx, span)
        }
        grid.descendantFocusability = if (fusion) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    fun onFusionUiChanged() {
        view?.let { FusionLandscapeShell.applyFragmentRoot(it, FusionUiMetrics.SidebarKind.Search) }
        view?.findViewById<RecyclerView>(R.id.searchRecycler)?.let { applySearchGrid(it) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    fun onHomeLayoutModeChanged() = onFusionUiChanged()
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshProgressHints()
        if (::adapter.isInitialized) adapter.refreshQueueState()
    }

    fun refreshFromParent() {
        val v = view ?: return
        val input = v.findViewById<TextInputEditText>(R.id.searchInput)
        runSearch(v, input.text?.toString().orEmpty())
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()
}
