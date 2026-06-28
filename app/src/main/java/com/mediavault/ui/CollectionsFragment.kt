package com.mediavault.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.mediavault.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CollectionsFragment : Fragment() {
    private lateinit var adapter: CollectionAdapter
    private lateinit var listPager: ListPagerBar
    private var tabMode: Int = 0
    private var cachedGroups: List<LibraryUi.CollectionGroup> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { tabMode = it.getInt(STATE_TAB, tabMode) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tabMode)
        if (::listPager.isInitialized) outState.putInt(STATE_PAGE, listPager.page)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_collections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listPager = ListPagerBar(view)
        listPager.bindHost(this)
        savedInstanceState?.getInt(STATE_PAGE)?.let { listPager.restorePage(it) }
        listPager.setOnPageChanged { applyPagedList(view) }

        val list = view.findViewById<RecyclerView>(R.id.collectionsRecycler)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)
        adapter = CollectionAdapter(viewLifecycleOwner.lifecycleScope) { g ->
            startActivity(CollectionDetailActivity.intent(requireContext(), g.key))
        }
        list.adapter = adapter

        val tabs = view.findViewById<TabLayout>(R.id.collectionsTabLayout)
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_series))
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_tags))
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_genres))
        if (savedInstanceState != null && tabMode in 0..2) {
            tabs.getTabAt(tabMode)?.select()
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabMode = tab.position
                listPager.resetPage()
                refreshList(view)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.library.collectLatest { lib ->
                refreshList(view, lib.items)
            }
        }
    }

    private fun refreshList(view: View, items: List<com.mediavault.data.MediaItem>? = null) {
        val act = activity as? MainActivity ?: return
        val all = items ?: act.repository.library.value.items
        cachedGroups = when (tabMode) {
            1 -> LibraryUi.tagCollectionGroups(all)
            2 -> LibraryUi.genreCollectionGroups(all)
            else -> LibraryUi.collectionGroups(all)
        }
        applyPagedList(view)
    }

    private fun applyPagedList(view: View) {
        val groups = cachedGroups
        listPager.update(groups.size, enabled = true)
        val list = view.findViewById<RecyclerView>(R.id.collectionsRecycler)
        adapter.submitList(listPager.slice(groups)) {
            list.scrollToPosition(0)
        }
        view.findViewById<TextView>(R.id.collectionsEmpty).apply {
            visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            text = when (tabMode) {
                1 -> getString(R.string.no_tag_collections)
                2 -> getString(R.string.no_genre_collections)
                else -> getString(R.string.no_collections)
            }
        }
    }

    companion object {
        private const val STATE_TAB = "collections_tab"
        private const val STATE_PAGE = "collections_page"
    }
}