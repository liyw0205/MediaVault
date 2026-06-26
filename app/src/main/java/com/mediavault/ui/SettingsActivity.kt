package com.mediavault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mediavault.R
import com.mediavault.data.MediaStore
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class SettingsActivity : AppCompatActivity() {
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
        setContentView(R.layout.activity_settings)
        store = MediaStore(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)

        remotes.clear()
        remotes.addAll(store.readRemotesList())

        findViewById<android.widget.Button>(R.id.pickLocalRootBtn).setOnClickListener { pickTree.launch(null) }
        findViewById<android.widget.Button>(R.id.addWebDavBtn).setOnClickListener {
            showRemoteDialog("webdav", null)
        }
        findViewById<android.widget.Button>(R.id.addFtpBtn).setOnClickListener { showRemoteDialog("ftp", null) }
        findViewById<android.widget.Button>(R.id.addSmbBtn).setOnClickListener { showRemoteDialog("smb", null) }
        findViewById<android.widget.Button>(R.id.saveBtn).setOnClickListener { saveRemotes() }
        findViewById<Button>(R.id.testWebDavBtn).setOnClickListener { testRemotePick() }

        refreshLocalList()
        refreshRemoteList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
                    MvDialog.builder(this@SettingsActivity)
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
                h.edit.setOnClickListener { showRemoteDialog(r.type, r) }
                h.delete.setOnClickListener {
                    MvDialog.builder(this@SettingsActivity)
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

    private fun showRemoteDialog(type: String, existing: RemoteConfig?) {
        val view = layoutInflater.inflate(R.layout.dialog_remote_form, null)
        val name = view.findViewById<TextInputEditText>(R.id.remoteName)
        val host = view.findViewById<TextInputEditText>(R.id.remoteHost)
        val port = view.findViewById<TextInputEditText>(R.id.remotePort)
        val user = view.findViewById<TextInputEditText>(R.id.remoteUser)
        val password = view.findViewById<TextInputEditText>(R.id.remotePassword)
        val base = view.findViewById<TextInputEditText>(R.id.remoteBase)
        val baseLayout = view.findViewById<TextInputLayout>(R.id.remoteBaseLayout)
        val defaultPort = when (type) {
            "ftp" -> "21"
            "smb" -> "445"
            "webdav" -> "5244"
            else -> "443"
        }
        if (existing != null) {
            name.setText(existing.name)
            host.setText(existing.host)
            port.setText(existing.port.toString())
            user.setText(existing.user)
            password.setText(existing.password)
            base.setText(existing.basePath)
        } else {
            port.setText(defaultPort)
            base.setText(RemoteBrowseDialogHelper.defaultBaseForType(type))
        }
        val browseHelper = RemoteBrowseDialogHelper(this) { chosen ->
            base.setText(chosen)
        }
        baseLayout.setEndIconOnClickListener {
            val h = host.text?.toString()?.trim().orEmpty()
            val p = port.text?.toString()?.toIntOrNull() ?: defaultPort.toInt()
            browseHelper.show(
                type = type,
                host = h,
                port = p,
                user = user.text?.toString().orEmpty(),
                password = password.text?.toString().orEmpty(),
                initialBasePath = base.text?.toString()?.trim().orEmpty(),
                savedConfigId = existing?.id,
            )
        }
        val title = if (existing == null) {
            getString(R.string.remote_dialog_add, type.uppercase())
        } else {
            getString(R.string.remote_dialog_edit)
        }
        MvDialog.showStyled(
            MvDialog.builder(this)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ ->
                val h = host.text?.toString()?.trim().orEmpty()
                if (h.isEmpty()) {
                    Toast.makeText(this, R.string.settings_need_host, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cfg = RemoteConfig(
                    id = existing?.id ?: UUID.randomUUID().toString().take(8),
                    type = type,
                    host = h,
                    port = port.text?.toString()?.toIntOrNull() ?: defaultPort.toInt(),
                    user = user.text?.toString().orEmpty(),
                    password = password.text?.toString().orEmpty(),
                    basePath = base.text?.toString()?.trim().orEmpty()
                        .ifBlank { RemoteBrowseDialogHelper.defaultBaseForType(type) },
                    name = name.text?.toString()?.trim().orEmpty().ifBlank { h },
                )
                if (existing != null) {
                    val idx = remotes.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) remotes[idx] = cfg
                } else {
                    remotes.add(cfg)
                }
                refreshRemoteList()
            },
            inputRoot = view,
        )
    }

    fun buildDraftRemoteConfig(
        type: String,
        host: String,
        port: Int,
        user: String,
        password: String,
        basePath: String,
    ): RemoteConfig = RemoteConfig(
        id = "browse",
        type = type,
        host = host,
        port = port,
        user = user,
        password = password,
        basePath = basePath,
        name = "browse",
    )

    private fun saveRemotes() {
        try {
            store.writeRemotesList(remotes)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
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
            .setItems(labels) { _, which ->
                testRemoteAt(which)
            }
            .show()
    }

    private fun testRemoteAt(index: Int) {
        val cfg = remotes.getOrNull(index) ?: return
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { RemoteClients.create(cfg).testConnection() }
                    .fold(onSuccess = { it }, onFailure = { getString(R.string.settings_test_failed, it.message ?: "") })
            }
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun testWebDav() {
        val cfg = remotes.firstOrNull { it.type == "webdav" }
            ?: run {
                Toast.makeText(this, R.string.settings_no_webdav, Toast.LENGTH_SHORT).show()
                return
            }
        lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) {
                    val client = RemoteClients.create(cfg)
                    val test = client.testConnection()
                    val list = client.list("").take(5).joinToString { it.name }
                    "$test\n${getString(R.string.settings_test_sample, list)}"
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_test_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
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