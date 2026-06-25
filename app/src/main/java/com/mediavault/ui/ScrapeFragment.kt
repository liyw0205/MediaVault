package com.mediavault.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mediavault.R
import com.mediavault.scrape.ScrapePhase
import kotlinx.coroutines.launch

class ScrapeFragment : Fragment() {
    private val pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val act = activity as? MainActivity ?: return@registerForActivityResult
        act.repository.store.appendLocalRootUri(uri.toString())
        refreshRoots()
        Toast.makeText(ctx, "已添加目录", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scrape, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.addRootBtn).setOnClickListener {
            pickRoot.launch(null)
        }
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
        refreshRoots()
        bindScrapeState(view)
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

    private fun refreshRoots() {
        val act = activity as? MainActivity ?: return
        val uris = act.repository.store.readLocalRootUris()
        view?.findViewById<TextView>(R.id.rootsList)?.text =
            if (uris.isEmpty()) "（未配置，请添加本地目录）" else uris.joinToString("\n") { shorten(it) }
    }

    private fun shorten(uri: String): String {
        val u = Uri.parse(uri)
        return u.lastPathSegment ?: uri.takeLast(48)
    }
}