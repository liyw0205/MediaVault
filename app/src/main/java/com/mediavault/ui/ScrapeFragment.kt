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
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.mediavault.R
import com.mediavault.data.ScrapeConfig
import com.mediavault.scrape.ScrapePhase
import kotlinx.coroutines.launch

class ScrapeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scrape, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.scanIncrementalBtn).setOnClickListener {
            startScrape(rebuild = false)
        }
        view.findViewById<MaterialButton>(R.id.scanRebuildBtn).setOnClickListener {
            startScrape(rebuild = true)
        }
        view.findViewById<MaterialButton>(R.id.stopScrapeBtn).setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            act.scrapeManager.cancel()
        }
        bindThreadSlider(view)
        refreshRoots()
        bindScrapeState(view)
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

    private fun startScrape(rebuild: Boolean) {
        val act = activity as? MainActivity ?: return
        if (act.scrapeManager.isRunning()) {
            Toast.makeText(requireContext(), "刮削已在后台运行", Toast.LENGTH_SHORT).show()
            return
        }
        act.scrapeManager.start(rebuild)
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
        val uris = act.repository.store.readLocalRootUris()
        view?.findViewById<TextView>(R.id.rootsList)?.text =
            if (uris.isEmpty()) getString(R.string.scrape_roots_empty) else uris.joinToString("\n") { shorten(it) }
    }

    private fun shorten(uri: String): String {
        val u = android.net.Uri.parse(uri)
        return u.lastPathSegment ?: uri.takeLast(48)
    }
}