package com.mediavault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.MediaStore
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemoteErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScrapeDirectoriesActivity : AppCompatActivity() {
    private lateinit var store: MediaStore
    private val remotes = mutableListOf<RemoteConfig>()

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        store.appendLocalRootUri(uri.toString())
        refreshLocalList()
        Toast.makeText(this, R.string.settings_added_local, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scrape_directories)
        store = MediaStore(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.scrape_dirs_title)

        remotes.clear()
        remotes.addAll(store.readRemotesList())

        findViewById<MaterialButton>(R.id.pickLocalRootBtn).setOnClickListener { pickTree.launch(null) }
        findViewById<MaterialButton>(R.id.addWebDavBtn).setOnClickListener {
            RemoteFormDialog.show(this, "webdav", null) { cfg -> upsertRemote(cfg) }
        }
        findViewById<MaterialButton>(R.id.addFtpBtn).setOnClickListener {
            RemoteFormDialog.show(this, "ftp", null) { cfg -> upsertRemote(cfg) }
        }
        findViewById<MaterialButton>(R.id.addSmbBtn).setOnClickListener {
            RemoteFormDialog.show(this, "smb", null) { cfg -> upsertRemote(cfg) }
        }
        findViewById<MaterialButton>(R.id.saveBtn).setOnClickListener { saveRemotes() }
        findViewById<MaterialButton>(R.id.testWebDavBtn).setOnClickListener { testRemotePick() }

        refreshLocalList()
        refreshRemoteList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun upsertRemote(cfg: RemoteConfig) {
        val idx = remotes.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) remotes[idx] = cfg else remotes.add(cfg)
        refreshRemoteList()
    }

    private fun refreshLocalList() {
        val rv = findViewById<RecyclerView>(R.id.localRootsList)
        rv.layoutManager = LinearLayoutManager(this)
        val uris = store.readLocalRootUris()
        rv.adapter = object : RecyclerView.Adapter<LocalVH>() {
            override fun getItemCount() = uris.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocalVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_local_root_row, parent, false)
                return LocalVH(v)
            }
            override fun onBindViewHolder(h: LocalVH, position: Int) {
                val uri = uris[position]
                val label = Uri.parse(uri).lastPathSegment ?: uri.takeLast(48)
                h.label.text = label
                h.delete.setOnClickListener {
                    MvDialog.builder(this@ScrapeDirectoriesActivity)
                        .setMessage(R.string.delete_local_root_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            store.removeLocalRootUri(uri)
                            refreshLocalList()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun refreshRemoteList() {
        val rv = findViewById<RecyclerView>(R.id.remotesList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = object : RecyclerView.Adapter<RemoteVH>() {
            override fun getItemCount() = remotes.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_row, parent, false)
                return RemoteVH(v)
            }
            override fun onBindViewHolder(h: RemoteVH, position: Int) {
                val r = remotes[position]
                h.name.text = r.name.ifBlank { r.id }
                h.meta.text = "${r.type.uppercase()} ${r.host}:${r.port} ${r.basePath}"
                h.edit.setOnClickListener {
                    RemoteFormDialog.show(this@ScrapeDirectoriesActivity, r.type, r) { cfg -> upsertRemote(cfg) }
                }
                h.delete.setOnClickListener {
                    MvDialog.builder(this@ScrapeDirectoriesActivity)
                        .setMessage(R.string.delete_remote_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            remotes.removeAt(position)
                            notifyDataSetChanged()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun saveRemotes() {
        try {
            store.writeRemotesList(remotes)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_save_failed, RemoteErrorMessages.userMessage(this, e)), Toast.LENGTH_LONG).show()
        }
    }

    private fun testRemotePick() {
        if (remotes.isEmpty()) {
            Toast.makeText(this, R.string.test_remote_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = remotes.map { r ->
            val t = r.type.uppercase()
            val name = r.name.ifBlank { r.host }
            "$name ($t)"
        }.toTypedArray()
        MvDialog.builder(this)
            .setTitle(R.string.test_remote_pick_title)
            .setItems(labels) { _, which -> testRemoteAt(which) }
            .show()
    }

    private fun testRemoteAt(index: Int) {
        val cfg = remotes.getOrNull(index) ?: return
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { RemoteClients.create(cfg).testConnection() }
                    .fold(onSuccess = { it }, onFailure = { getString(R.string.settings_test_failed, RemoteErrorMessages.userMessage(this@ScrapeDirectoriesActivity, it)) })
            }
            Toast.makeText(this@ScrapeDirectoriesActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private class LocalVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.localRootLabel)
        val delete: MaterialButton = v.findViewById(R.id.btnDeleteLocalRoot)
    }

    private class RemoteVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.remoteName)
        val meta: TextView = v.findViewById(R.id.remoteMeta)
        val edit: MaterialButton = v.findViewById(R.id.btnEditRemote)
        val delete: MaterialButton = v.findViewById(R.id.btnDeleteRemote)
    }
}