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
import com.mediavault.R
import com.mediavault.data.MediaItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CollectionsFragment : Fragment() {
    private lateinit var adapter: CollectionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_collections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CollectionAdapter { g ->
            startActivity(CollectionDetailActivity.intent(requireContext(), g.key))
        }
        view.findViewById<RecyclerView>(R.id.collectionsRecycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CollectionsFragment.adapter
        }
        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.library.collectLatest { lib ->
                val groups = LibraryUi.collectionGroups(lib.items)
                adapter.submitList(groups)
                view.findViewById<TextView>(R.id.collectionsEmpty).visibility =
                    if (groups.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    fun refreshFromParent() {
        view ?: return
        val act = activity as? MainActivity ?: return
        val groups = LibraryUi.collectionGroups(act.repository.library.value.items)
        adapter.submitList(groups)
    }
}