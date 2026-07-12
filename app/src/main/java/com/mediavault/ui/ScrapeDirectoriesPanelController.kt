package com.mediavault.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mediavault.R
import com.mediavault.data.MediaStore
import com.mediavault.data.MediaLibrary
import com.mediavault.data.RemoteMappingPreview
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemoteErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本机 SAF 根与远程配置，内嵌在刮削设置侧栏（不再跳转全屏 Activity）。
 */
class ScrapeDirectoriesPanelController(
    private val activity: AppCompatActivity,
    private var panelRoot: View,
    private var onRootsChanged: () -> Unit,
    private var pickLocalTree: () -> Unit,
) {
    private val store = MediaStore(activity)
    private val remotes = mutableListOf<RemoteConfig>()

    fun onLocalTreePicked(uri: Uri?) {
        if (uri == null) return
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        store.appendLocalRootUri(uri.toString())
        refreshLocalList()
        notifyRootsChanged()
        Toast.makeText(activity, R.string.settings_added_local, Toast.LENGTH_SHORT).show()
    }

    fun bind() {
        remotes.clear()
        remotes.addAll(store.readRemotesList())

        panelRoot.findViewById<MaterialButton>(R.id.drawerPickLocalRootBtn).setOnClickListener {
            pickLocalTree()
        }
        panelRoot.findViewById<MaterialButton>(R.id.drawerAddWebDavBtn).setOnClickListener {
            RemoteFormDialog.show(activity, "webdav", null) { cfg -> upsertRemote(cfg) }
        }
        panelRoot.findViewById<MaterialButton>(R.id.drawerAddFtpBtn).setOnClickListener {
            RemoteFormDialog.show(activity, "ftp", null) { cfg -> upsertRemote(cfg) }
        }
        panelRoot.findViewById<MaterialButton>(R.id.drawerAddSmbBtn).setOnClickListener {
            RemoteFormDialog.show(activity, "smb", null) { cfg -> upsertRemote(cfg) }
        }
        panelRoot.findViewById<MaterialButton>(R.id.drawerSaveRemotesBtn).setOnClickListener { saveRemotes() }
        panelRoot.findViewById<MaterialButton>(R.id.drawerTestRemoteBtn).setOnClickListener { testRemotePick() }

        refreshLocalList()
        refreshRemoteList()
    }

    fun reload() {
        remotes.clear()
        remotes.addAll(store.readRemotesList())
        refreshLocalList()
        refreshRemoteList()
    }

    fun promptCredentialCompletion(remoteIds: List<String>): Boolean {
        val wanted = remoteIds.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return false
        remotes.clear()
        remotes.addAll(store.readRemotesList())
        val targets = remotes.filter { it.id in wanted }
        if (targets.isEmpty()) return false
        if (targets.size == 1) {
            openRemoteEditor(targets.first(), saveImmediately = true)
            return true
        }
        val labels = targets.map { r ->
            val name = r.name.ifBlank { r.id }
            "$name (${r.type.uppercase()} ${r.host})"
        }.toTypedArray()
        MvDialog.show(
            MvDialog.builder(activity)
                .setTitle(R.string.backup_import_pick_remote_credential)
                .setItems(labels) { _, which ->
                    targets.getOrNull(which)?.let { openRemoteEditor(it, saveImmediately = true) }
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
        return true
    }

    /** 横竖屏重载主壳后，侧栏 View 已换新，需重绑列表与按钮。 */
    fun rebindPanelRoot(
        newSection: View,
        onRootsChanged: () -> Unit,
        pickLocalTree: () -> Unit,
    ) {
        panelRoot = newSection
        this.onRootsChanged = onRootsChanged
        this.pickLocalTree = pickLocalTree
        bind()
    }

    private fun notifyRootsChanged() {
        onRootsChanged()
    }

    private fun upsertRemote(cfg: RemoteConfig) {
        val idx = remotes.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) remotes[idx] = cfg else remotes.add(cfg)
        refreshRemoteList()
    }

    private fun refreshLocalList() {
        val rv = panelRoot.findViewById<RecyclerView>(R.id.drawerLocalRootsList)
        rv.layoutManager = LinearLayoutManager(activity)
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
                    MvDialog.show(
                        MvDialog.builder(activity)
                        .setMessage(R.string.delete_local_root_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            store.removeLocalRootUri(uri)
                            refreshLocalList()
                            notifyRootsChanged()
                        }
                        .setNegativeButton(android.R.string.cancel, null),
                    )
                }
            }
        }
    }

    private fun refreshRemoteList() {
        val rv = panelRoot.findViewById<RecyclerView>(R.id.drawerRemotesList)
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = object : RecyclerView.Adapter<RemoteVH>() {
            override fun getItemCount() = remotes.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_row, parent, false)
                return RemoteVH(v)
            }
            override fun onBindViewHolder(h: RemoteVH, position: Int) {
                val r = remotes[position]
                h.name.text = r.name.ifBlank { r.id }
                val meta = "${r.type.uppercase()} ${r.host}:${r.port} ${r.basePath}"
                if (r.credentialMissing) {
                    h.meta.text = activity.getString(R.string.remote_credential_missing_fmt, meta)
                    h.meta.setTextColor(activity.getColor(R.color.mv_amber))
                } else {
                    h.meta.text = meta
                    h.meta.setTextColor(activity.getColor(R.color.mv_text_secondary))
                }
                h.edit.setOnClickListener {
                    openRemoteEditor(r)
                }
                h.delete.setOnClickListener {
                    MvDialog.show(
                        MvDialog.builder(activity)
                            .setMessage(R.string.delete_remote_confirm)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                remotes.removeAll { it.id == r.id }
                                refreshRemoteList()
                            }
                            .setNegativeButton(android.R.string.cancel, null),
                    )
                }
            }
        }
    }

    private fun openRemoteEditor(remote: RemoteConfig, saveImmediately: Boolean = false) {
        RemoteFormDialog.show(activity, remote.type, remote) { cfg ->
            val items = store.readLibraryText()?.let { MediaLibrary.parse(it, store.libraryFile.absolutePath).items }.orEmpty()
            val preview = RemoteMappingPreview.create(remote, cfg, items)
            val apply = {
                upsertRemote(cfg)
                if (saveImmediately) saveRemotes()
            }
            if (!preview.needsConfirmation) {
                apply()
                return@show
            }
            val samples = preview.samplePaths.joinToString("\n")
            MvDialog.show(
                MvDialog.builder(activity)
                    .setTitle(R.string.remote_mapping_preview_title)
                    .setMessage(activity.getString(R.string.remote_mapping_preview_message_fmt, preview.affectedCount, samples))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remote_mapping_preview_apply) { _, _ -> apply() },
            )
        }
    }

    private fun saveRemotes() {
        try {
            store.writeRemotesList(remotes)
            Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            notifyRootsChanged()
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.settings_save_failed, RemoteErrorMessages.userMessage(activity, e)),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun testRemotePick() {
        if (remotes.isEmpty()) {
            Toast.makeText(activity, R.string.test_remote_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = remotes.map { r ->
            val t = r.type.uppercase()
            val name = r.name.ifBlank { r.host }
            "$name ($t)"
        }.toTypedArray()
        MvDialog.show(
            MvDialog.builder(activity)
                .setTitle(R.string.test_remote_pick_title)
                .setItems(labels) { _, which -> testRemoteAt(which) },
        )
    }

    private fun testRemoteAt(index: Int) {
        val cfg = remotes.getOrNull(index) ?: return
        activity.lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { RemoteClients.create(cfg).testConnection() }
                    .fold(
                        onSuccess = { it },
                        onFailure = {
                            activity.getString(
                                R.string.settings_test_failed,
                                RemoteErrorMessages.userMessage(activity, it),
                            )
                        },
                    )
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
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
