package com.mediavault.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.google.android.material.textfield.TextInputEditText
import com.mediavault.R
import com.mediavault.data.MediaItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    private lateinit var adapter: VideoCardAdapter

    companion object {
        private const val ARG_QUERY = "q"
        fun newInstance(query: String) = SearchFragment().apply {
            arguments = Bundle().apply { putString(ARG_QUERY, query) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        val span = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 2
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        adapter = VideoCardAdapter(
            onCoverClick = { openDetail(it) },
            onInfoClick = { openDetail(it) },
        )
        grid.adapter = adapter

        val input = view.findViewById<TextInputEditText>(R.id.searchInput)
        arguments?.getString(ARG_QUERY)?.let { input.setText(it) }
        view.findViewById<MaterialButton>(R.id.clearSearchBtn).setOnClickListener {
            input.text?.clear()
            runSearch(view, "")
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runSearch(view, s?.toString() ?: "")
            }
        })

        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            act.repository.library.collectLatest { lib ->
                runSearch(view, input.text?.toString() ?: "", lib.items)
            }
        }
    }

    private fun runSearch(view: View, query: String, items: List<MediaItem>? = null) {
        val all = items ?: (activity as MainActivity).repository.library.value.items
        val q = query.trim()
        val hits = if (q.isBlank()) emptyList() else LibraryUi.search(all, query)
        adapter.submitList(hits)

        val tagGroup = view.findViewById<ChipGroup>(R.id.matchedTags)
        val grid = view.findViewById<RecyclerView>(R.id.searchRecycler)
        val countTv = view.findViewById<TextView>(R.id.searchCount)

        if (q.isBlank()) {
            grid.visibility = View.GONE
            tagGroup.visibility = View.VISIBLE
            countTv.text = getString(R.string.search_tags_only_hint, LibraryUi.allTags(all).size)
            tagGroup.removeAllViews()
            for (t in LibraryUi.allTags(all)) {
                val chip = Chip(requireContext())
                chip.text = t
                chip.isClickable = true
                chip.textSize = 11f
                chip.setOnClickListener {
                    view.findViewById<TextInputEditText>(R.id.searchInput)?.setText(t)
                }
                tagGroup.addView(chip)
            }
        } else {
            grid.visibility = View.VISIBLE
            tagGroup.visibility = View.VISIBLE
            countTv.text = getString(R.string.search_count, hits.size)
            tagGroup.removeAllViews()
            for (t in LibraryUi.matchedTags(all, query)) {
                val chip = Chip(requireContext())
                chip.text = t
                chip.isClickable = true
                chip.textSize = 11f
                chip.setOnClickListener {
                    view.findViewById<TextInputEditText>(R.id.searchInput)?.setText(t)
                }
                tagGroup.addView(chip)
            }
        }
    }

    private fun openDetail(item: MediaItem) {
        startActivity(VideoDetailActivity.intent(requireContext(), item.path))
    }

    fun refreshFromParent() {
        view ?: return
        val input = view!!.findViewById<TextInputEditText>(R.id.searchInput)
        runSearch(view!!, input.text?.toString() ?: "")
    }
}