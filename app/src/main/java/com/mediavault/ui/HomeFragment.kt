package com.mediavault.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private lateinit var adapter: VideoCardAdapter
    private var homeMode = "recommend"
    private var selectedRoot: String? = null
    private var page = 1
    private var recommendSeed = System.currentTimeMillis()
    private val historyStore by lazy { HistoryStore(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val grid = view.findViewById<RecyclerView>(R.id.gridRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        adapter = VideoCardAdapter(
            onClick = { openItem(it) },
            onLongClick = { startActivity(VideoDetailActivity.intent(requireContext(), it.path)) },
        )
        grid.adapter = adapter

        view.findViewById<MaterialButton>(R.id.prevPageBtn).setOnClickListener { changePage(-1) }
        view.findViewById<MaterialButton>(R.id.nextPageBtn).setOnClickListener { changePage(1) }
        view.findViewById<MaterialButton>(R.id.homePagerActionBtn).setOnClickListener { onPagerAction() }
        view.findViewById<MaterialButton>(R.id.modeRecommendBtn).setOnClickListener {
            setHomeMode("recommend")
        }
        view.findViewById<MaterialButton>(R.id.modeHistoryBtn).setOnClickListener {
            setHomeMode("history")
        }

        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.library.collectLatest { lib ->
                bindLibrary(view, lib.items)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.updatedAt.collectLatest { t ->
                view.findViewById<TextView>(R.id.statTime).text = t
            }
        }
    }

    fun refreshFromParent() {
        view?.let { v ->
            val act = activity as? MainActivity ?: return
            bindLibrary(v, act.repository.library.value.items)
        }
    }

    private fun bindLibrary(view: View, items: List<MediaItem>) {
        val filtered = LibraryUi.filterByRoot(items, selectedRoot)
        view.findViewById<TextView>(R.id.statCollections).text =
            LibraryUi.distinctCollections(items).toString()
        view.findViewById<TextView>(R.id.statItems).text = items.size.toString()
        view.findViewById<TextView>(R.id.statRoots).text = LibraryUi.distinctRoots(items).size.toString()

        rebuildRootChips(view, items)
        val list = currentList(filtered)
        val pages = (list.size + LibraryUi.PAGE_SIZE - 1) / LibraryUi.PAGE_SIZE
        if (page > pages.coerceAtLeast(1)) page = 1
        val slice = paginate(list, page)
        adapter.submitList(slice)
        view.findViewById<TextView>(R.id.emptyText).visibility =
            if (slice.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.pageInfo).text =
            getString(R.string.page_fmt, page, pages.coerceAtLeast(1))
        view.findViewById<TextView>(R.id.statusText).text =
            getString(R.string.items_count, list.size)
        updatePagerActionLabel(view)
    }

    private fun rebuildRootChips(view: View, items: List<MediaItem>) {
        val group = view.findViewById<ChipGroup>(R.id.rootFilters)
        group.removeAllViews()
        val ctx = requireContext()
        fun addChip(label: String, value: String?) {
            val chip = Chip(ctx)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = selectedRoot == value
            chip.setOnClickListener {
                selectedRoot = value
                page = 1
                bindLibrary(view, items)
            }
            group.addView(chip)
        }
        addChip("全部", null)
        for (r in LibraryUi.distinctRoots(items)) addChip(r, r)
    }

    private fun currentList(filtered: List<MediaItem>): List<MediaItem> = when (homeMode) {
        "history" -> LibraryUi.historyItems(
            (activity as MainActivity).repository.library.value.items,
            historyStore.list(),
        )
        else -> LibraryUi.recommend(filtered, recommendSeed)
    }

    private fun paginate(list: List<MediaItem>, page: Int): List<MediaItem> {
        val start = (page - 1) * LibraryUi.PAGE_SIZE
        if (start >= list.size) return emptyList()
        return list.subList(start, minOf(start + LibraryUi.PAGE_SIZE, list.size))
    }

    private fun changePage(delta: Int) {
        val v = view ?: return
        val items = LibraryUi.filterByRoot(
            (activity as MainActivity).repository.library.value.items,
            selectedRoot,
        )
        val list = currentList(items)
        val pages = (list.size + LibraryUi.PAGE_SIZE - 1) / LibraryUi.PAGE_SIZE
        page = (page + delta).coerceIn(1, pages.coerceAtLeast(1))
        bindLibrary(v, (activity as MainActivity).repository.library.value.items)
    }

    private fun onPagerAction() {
        if (homeMode == "history") {
            historyStore.clear()
        } else {
            recommendSeed = System.currentTimeMillis()
            page = 1
        }
        refreshFromParent()
    }

    private fun updatePagerActionLabel(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.homePagerActionBtn)
        btn.text = if (homeMode == "history") getString(R.string.clear_history)
        else getString(R.string.refresh_recommend)
    }

    fun setHomeMode(mode: String) {
        homeMode = mode
        page = 1
        refreshFromParent()
    }

    private fun openItem(item: MediaItem) {
        historyStore.add(item.path)
        val act = activity as? MainActivity ?: return
        act.playItem(item)
    }
}