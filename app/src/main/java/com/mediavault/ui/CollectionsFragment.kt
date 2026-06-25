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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CollectionsFragment : Fragment() {
    private lateinit var adapter: CollectionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_collections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.collectionsRecycler)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = CollectionAdapter { g ->
            startActivity(CollectionDetailActivity.intent(requireContext(), g.key))
        }
        list.adapter = adapter

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
}