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
    private var tabMode: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_collections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.collectionsRecycler)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = CollectionAdapter { g ->
            startActivity(CollectionDetailActivity.intent(requireContext(), g.key))
        }
        list.adapter = adapter

        val tabs = view.findViewById<TabLayout>(R.id.collectionsTabLayout)
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_series))
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_tags))
        tabs.addTab(tabs.newTab().setText(R.string.collections_tab_genres))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabMode = tab.position
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
        val groups = when (tabMode) {
            1 -> LibraryUi.tagCollectionGroups(all)
            2 -> LibraryUi.genreCollectionGroups(all)
            else -> LibraryUi.collectionGroups(all)
        }
        adapter.submitList(groups)
        view.findViewById<TextView>(R.id.collectionsEmpty).apply {
            visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            text = when (tabMode) {
                1 -> getString(R.string.no_tag_collections)
                2 -> getString(R.string.no_genre_collections)
                else -> getString(R.string.no_collections)
            }
        }
    }
}