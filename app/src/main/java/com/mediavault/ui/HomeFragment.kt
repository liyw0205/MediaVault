package com.mediavault.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    /** recommend | history | all | root:<key> */
    private var homeFilter = "recommend"
    private var page = 1
    private var recommendSeed = System.currentTimeMillis()
    private var recommendCache: List<MediaItem>? = null
    private var recommendCacheKey: String? = null
    private var recommendInitialized = false
    private val historyStore by lazy { HistoryStore(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val grid = view.findViewById<RecyclerView>(R.id.gridRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        adapter = VideoCardAdapter(
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
        )
        grid.adapter = adapter

        view.findViewById<MaterialButton>(R.id.prevPageBtn).setOnClickListener { changePage(-1) }
        view.findViewById<MaterialButton>(R.id.nextPageBtn).setOnClickListener { changePage(1) }
        view.findViewById<MaterialButton>(R.id.homePagerActionBtn).setOnClickListener { onPagerAction() }
        view.findViewById<TextView>(R.id.pageInfo).setOnClickListener { showPageJumpDialog() }

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
        view.findViewById<TextView>(R.id.statCollections).text =
            LibraryUi.distinctCollections(items).toString()
        view.findViewById<TextView>(R.id.statItems).text = items.size.toString()
        view.findViewById<TextView>(R.id.statRoots).text = LibraryUi.distinctRoots(items).size.toString()

        rebuildFilterChips(view, items)
        val list = currentList(items)
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
        val actionBtn = view.findViewById<MaterialButton>(R.id.homePagerActionBtn)
        when (homeFilter) {
            "history" -> {
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.clear_history)
            }
            "recommend" -> {
                actionBtn.visibility = View.VISIBLE
                actionBtn.text = getString(R.string.refresh_recommend)
            }
            else -> actionBtn.visibility = View.GONE
        }
        val pager = view.findViewById<View>(R.id.homePager)
        pager.visibility = if (list.size > LibraryUi.PAGE_SIZE || page > 1) View.VISIBLE else View.VISIBLE
        view.findViewById<MaterialButton>(R.id.prevPageBtn).isEnabled = page > 1
        view.findViewById<MaterialButton>(R.id.nextPageBtn).isEnabled = page < pages.coerceAtLeast(1)
    }

    private fun selectedRoot(): String? = when {
        homeFilter.startsWith("root:") -> homeFilter.removePrefix("root:")
        else -> null
    }

    private fun scopedItems(all: List<MediaItem>): List<MediaItem> =
        LibraryUi.filterByRoot(all, selectedRoot())

    private fun cacheKey(filtered: List<MediaItem>): String =
        "${homeFilter}:${filtered.size}:${filtered.firstOrNull()?.path}:${filtered.lastOrNull()?.path}"

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
                if (filterId != "recommend") {
                    // 换到历史/全部/目录不重算推荐种子
                } else if (recommendCacheKey != null && filterId == "recommend") {
                    // 保持缓存
                }
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
            "recommend" -> {
                val key = cacheKey(filtered)
                if (!recommendInitialized) {
                    recommendSeed = System.currentTimeMillis()
                    recommendCache = LibraryUi.recommend(filtered, recommendSeed)
                    recommendCacheKey = key
                    recommendInitialized = true
                } else if (recommendCacheKey != key) {
                    recommendCache = LibraryUi.recommend(filtered, recommendSeed)
                    recommendCacheKey = key
                }
                recommendCache ?: emptyList()
            }
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
        return (list.size + LibraryUi.PAGE_SIZE - 1) / LibraryUi.PAGE_SIZE
    }

    private fun changePage(delta: Int) {
        val v = view ?: return
        val pages = totalPages().coerceAtLeast(1)
        page = (page + delta).coerceIn(1, pages)
        bindLibrary(v, (activity as MainActivity).repository.library.value.items)
    }

    private fun showPageJumpDialog() {
        val pages = totalPages().coerceAtLeast(1)
        if (pages <= 1) return
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(page.toString())
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.page_jump_title)
            .setMessage(getString(R.string.page_jump_hint, pages))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                page = n.coerceIn(1, pages)
                refreshFromParent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onPagerAction() {
        when (homeFilter) {
            "history" -> historyStore.clear()
            "recommend" -> {
                recommendSeed = System.currentTimeMillis()
                recommendCache = null
                recommendCacheKey = null
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

    private fun openDetail(item: MediaItem) {
        startActivity(VideoDetailActivity.intent(requireContext(), item.path))
    }
}