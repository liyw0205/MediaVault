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
import com.google.android.material.slider.Slider
import com.mediavault.R
import com.mediavault.data.ScrapeConfig
import com.mediavault.remote.RemotePath
import com.mediavault.scrape.ScrapePhase
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
        bindThreadSlider(view)
        bindRemoteFrameSlider(view)
        refreshRoots()
        bindScrapeState(view)
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

    private fun bindThreadSlider(view: View) {
        val slider = view.findViewById<Slider>(R.id.scrapeThreadSlider)
        val label = view.findViewById<TextView>(R.id.scrapeThreadValue)
        val ctx = requireContext()
        val cur = ScrapeConfig.readThreadCount(ctx).toFloat()
        slider.value = cur
        label.text = getString(R.string.scrape_threads_fmt, cur.toInt())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val n = value.toInt().coerceIn(ScrapeConfig.MIN_THREADS, ScrapeConfig.MAX_THREADS)
            ScrapeConfig.writeThreadCount(ctx, n)
            label.text = getString(R.string.scrape_threads_fmt, n)
        }
    }

    private fun bindRemoteFrameSlider(view: View) {
        val slider = view.findViewById<Slider>(R.id.scrapeRemoteFrameSlider)
        val label = view.findViewById<TextView>(R.id.scrapeRemoteFrameValue)
        val ctx = requireContext()
        val cur = ScrapeConfig.readRemoteFrameConcurrency(ctx).toFloat()
        slider.value = cur
        label.text = getString(R.string.scrape_remote_frame_fmt, cur.toInt())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val n = value.toInt().coerceIn(ScrapeConfig.MIN_REMOTE_FRAME, ScrapeConfig.MAX_REMOTE_FRAME)
            ScrapeConfig.writeRemoteFrameConcurrency(ctx, n)
            label.text = getString(R.string.scrape_remote_frame_fmt, n)
        }
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
                    applyState(view, s.phase, s.message, s.canResume)
                }
            }
        }
    }

    private fun applyState(view: View, phase: ScrapePhase, message: String, canResume: Boolean) {
        val status = view.findViewById<TextView>(R.id.scanStatus)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.scanProgress)
        val stop = view.findViewById<MaterialButton>(R.id.stopScrapeBtn)
        val inc = view.findViewById<MaterialButton>(R.id.scanIncrementalBtn)
        val reb = view.findViewById<MaterialButton>(R.id.scanRebuildBtn)
        when (phase) {
            ScrapePhase.RUNNING -> {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = true
                stop.visibility = View.VISIBLE
                inc.isEnabled = false
                reb.isEnabled = false
                status.text = message.ifBlank { getString(R.string.scrape_running_banner) }
            }
            ScrapePhase.DONE -> {
                bar.visibility = View.GONE
                stop.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                status.text = message
            }
            ScrapePhase.ERROR, ScrapePhase.CANCELLED -> {
                bar.visibility = View.GONE
                stop.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                status.text = message
            }
            ScrapePhase.IDLE -> {
                bar.visibility = View.GONE
                stop.visibility = View.GONE
                inc.isEnabled = true
                reb.isEnabled = true
                status.text = when {
                    canResume -> message.ifBlank { "可点「全部刮削」从刮削记录续扫" }
                    else -> getString(R.string.scan_idle)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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