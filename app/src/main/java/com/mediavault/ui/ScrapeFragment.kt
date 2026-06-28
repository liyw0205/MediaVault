package com.mediavault.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mediavault.R
import com.mediavault.remote.RemotePath
import com.mediavault.scrape.ScrapePhase
import com.mediavault.scrape.ScrapeProgressFormat
import com.mediavault.scrape.ScrapeUiState
import kotlinx.coroutines.launch

class ScrapeFragment : Fragment() {
    private var rootsAdapter: ScrapeRootAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scrape, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = activity as? MainActivity ?: return
        val rv = view.findViewById<RecyclerView>(R.id.rootsRecycler)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rootsAdapter = ScrapeRootAdapter(
            store = act.repository.store,
            onIncrementalLocal = { uri -> startScrape(rebuild = false, localRoots = listOf(uri), remoteIds = null) },
            onRescanLocal = { uri -> confirmRescanLocal(uri) },
            onRemoveLocal = { uri, n -> confirmRemoveLocal(uri, n) },
            onIncrementalRemote = { id -> startScrape(rebuild = false, localRoots = null, remoteIds = listOf(id)) },
            onRescanRemote = { id -> confirmRescanRemote(id) },
            onRemoveRemote = { id, n -> confirmRemoveRemote(id, n) },
            itemCountForLocal = { uri -> countItemsUnderLocal(act, uri) },
            itemCountForRemote = { id -> countItemsUnderRemote(act, id) },
        )
        rv.adapter = rootsAdapter

        view.findViewById<MaterialButton>(R.id.scanIncrementalBtn).setOnClickListener {
            startScrape(rebuild = false, localRoots = null, remoteIds = null)
        }
        view.findViewById<MaterialButton>(R.id.scanRebuildBtn).setOnClickListener {
            startScrape(rebuild = true, localRoots = null, remoteIds = null)
        }
        view.findViewById<MaterialButton>(R.id.stopScrapeBtn).setOnClickListener {
            act.scrapeManager.cancel()
        }
        view.findViewById<MaterialButton>(R.id.collapseScrapeOverlayBtn).setOnClickListener {
            setOverlayExpanded(false)
            applyRunningOverlayLayout(view, true)
        }
        view.findViewById<MaterialButton>(R.id.expandScrapeOverlayBtn).setOnClickListener {
            setOverlayExpanded(true)
            applyRunningOverlayLayout(view, true)
        }
        refreshRoots()
        bindScrapeState(view)
    }

    private fun setOverlayExpanded(expanded: Boolean) {
        ScrapeUiPrefs.setProgressOverlayExpanded(requireContext(), expanded)
    }

    private fun isOverlayExpanded(): Boolean =
        ScrapeUiPrefs.isProgressOverlayExpanded(requireContext())

    private fun applyRunningOverlayLayout(view: View, running: Boolean) {
        val overlay = view.findViewById<View>(R.id.scrapeProgressOverlay)
        val collapsed = view.findViewById<View>(R.id.scrapeProgressCollapsed)
        if (!running) {
            overlay.visibility = View.GONE
            collapsed.visibility = View.GONE
            return
        }
        if (isOverlayExpanded()) {
            overlay.visibility = View.VISIBLE
            collapsed.visibility = View.GONE
        } else {
            overlay.visibility = View.GONE
            collapsed.visibility = View.VISIBLE
        }
    }

    private fun countItemsUnderLocal(act: MainActivity, rootUri: String): Int =
        act.repository.library.value.items.count {
            it.path.startsWith("content://") && it.path.startsWith(rootUri)
        }

    private fun countItemsUnderRemote(act: MainActivity, remoteId: String): Int {
        val prefix = RemotePath.PREFIX + remoteId + "|"
        return act.repository.library.value.items.count { it.path.startsWith(prefix) }
    }

    private fun confirmRescanLocal(uri: String) {
        MvDialog.builder(requireContext())
            .setMessage(R.string.scrape_root_rescan_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val act = activity as? MainActivity ?: return@setPositiveButton
                act.repository.store.clearScrapeRecordsUnderRoot(uri)
                act.repository.removeItemsUnderRoot(uri).onFailure {
                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                startScrape(rebuild = true, localRoots = listOf(uri), remoteIds = null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRescanRemote(remoteId: String) {
        MvDialog.builder(requireContext())
            .setMessage(R.string.scrape_root_rescan_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val act = activity as? MainActivity ?: return@setPositiveButton
                act.repository.store.clearScrapeRecordsUnderRemote(remoteId)
                act.repository.removeItemsUnderRemote(remoteId).onFailure {
                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                startScrape(rebuild = true, localRoots = null, remoteIds = listOf(remoteId))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveLocal(uri: String, n: Int) {
        MvDialog.builder(requireContext())
            .setMessage(getString(R.string.scrape_root_clear_confirm, n))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val act = activity as? MainActivity ?: return@setPositiveButton
                act.repository.store.clearScrapeRecordsUnderRoot(uri)
                act.repository.removeItemsUnderRoot(uri)
                    .onSuccess { removed ->
                        refreshRoots()
                        Toast.makeText(requireContext(), getString(R.string.scrape_root_cleared_fmt, removed), Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { Toast.makeText(requireContext(), it.message, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveRemote(remoteId: String, n: Int) {
        MvDialog.builder(requireContext())
            .setMessage(getString(R.string.scrape_root_clear_confirm, n))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val act = activity as? MainActivity ?: return@setPositiveButton
                act.repository.store.clearScrapeRecordsUnderRemote(remoteId)
                act.repository.removeItemsUnderRemote(remoteId)
                    .onSuccess { removed ->
                        refreshRoots()
                        Toast.makeText(requireContext(), getString(R.string.scrape_root_cleared_fmt, removed), Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { Toast.makeText(requireContext(), it.message, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startScrape(rebuild: Boolean, localRoots: List<String>?, remoteIds: List<String>?) {
        val act = activity as? MainActivity ?: return
        if (act.scrapeManager.isRunning()) {
            Toast.makeText(requireContext(), R.string.scrape_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        act.scrapeManager.start(rebuild, localRoots, remoteIds)
    }

    private fun bindScrapeState(view: View) {
        val act = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                act.scrapeManager.state.collect { s ->
                    applyState(view, s)
                }
            }
        }
    }

    private fun applyState(view: View, s: ScrapeUiState) {
        val overlay = view.findViewById<View>(R.id.scrapeProgressOverlay)
        val status = view.findViewById<TextView>(R.id.scanStatus)
        val currentFile = view.findViewById<TextView>(R.id.scanCurrentFile)
        val collapsedCount = view.findViewById<TextView>(R.id.scrapeProgressCollapsedCount)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.scanProgress)
        val idleHint = view.findViewById<TextView>(R.id.scanIdleHint)
        val inc = view.findViewById<MaterialButton>(R.id.scanIncrementalBtn)
        val reb = view.findViewById<MaterialButton>(R.id.scanRebuildBtn)
        val ctx = requireContext()
        val countLine = ScrapeProgressFormat.countLine(ctx, s.batchCount, s.totalInLibrary)
        val prep = ScrapeProgressFormat.isPrepStatus(s.message)
        when (s.phase) {
            ScrapePhase.RUNNING -> {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = true
                inc.isEnabled = false
                reb.isEnabled = false
                status.text = countLine
                val file = s.currentFileLabel.trim()
                if (prep && s.message.isNotBlank()) {
                    currentFile.visibility = View.VISIBLE
                    currentFile.text = ScrapeProgressFormat.ellipsizeFileName(s.message)
                } else if (file.isNotEmpty()) {
                    currentFile.visibility = View.VISIBLE
                    currentFile.text = getString(R.string.scrape_progress_current_file, file)
                } else {
                    currentFile.visibility = View.GONE
                    currentFile.text = ""
                }
                collapsedCount?.text = ScrapeProgressFormat.collapsedCompact(s.batchCount, s.totalInLibrary)
                idleHint.text = ""
                applyRunningOverlayLayout(view, true)
            }
            ScrapePhase.DONE -> {
                view.findViewById<View>(R.id.scrapeProgressCollapsed).visibility = View.GONE
                overlay.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                idleHint.text = s.message
            }
            ScrapePhase.ERROR, ScrapePhase.CANCELLED -> {
                view.findViewById<View>(R.id.scrapeProgressCollapsed).visibility = View.GONE
                overlay.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                idleHint.text = s.message
            }
            ScrapePhase.IDLE -> {
                view.findViewById<View>(R.id.scrapeProgressCollapsed).visibility = View.GONE
                overlay.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                idleHint.text = when {
                    s.canResume -> s.message.ifBlank { getString(R.string.scrape_resume_idle) }
                    else -> ""
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRoots()
    }

    fun refreshRootsFromOutside() {
        refreshRoots()
    }

    private fun refreshRoots() {
        val act = activity as? MainActivity ?: return
        val rows = ScrapeRootAdapter.rowsFor(act.repository.store, requireContext())
        rootsAdapter?.submitList(rows)
        val emptyHint = view?.findViewById<TextView>(R.id.rootsEmptyHint)
        if (emptyHint != null) {
            emptyHint.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}