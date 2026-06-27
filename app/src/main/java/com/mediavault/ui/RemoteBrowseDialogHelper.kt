package com.mediavault.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mediavault.R
import com.mediavault.remote.RemoteBrowsePreviewHolder
import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteEntry
import com.mediavault.remote.RemoteMediaTypes
import com.mediavault.remote.RemotePlayUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteBrowseDialogHelper(
    private val activity: AppCompatActivity,
    private val draftConfigProvider: () -> com.mediavault.remote.RemoteConfig,
    private val onPathSelected: (String) -> Unit,
) {
    fun show(
        type: String,
        host: String,
        port: Int,
        user: String,
        password: String,
        initialBasePath: String,
        savedConfigId: String? = null,
    ) {
        if (host.isBlank()) {
            Toast.makeText(activity, R.string.remote_browse_need_host, Toast.LENGTH_SHORT).show()
            return
        }
        val view = activity.layoutInflater.inflate(R.layout.dialog_remote_browse, null)
        val pathTv = view.findViewById<TextView>(R.id.remoteBrowsePath)
        val list = view.findViewById<RecyclerView>(R.id.remoteBrowseList)
        val upBtn = view.findViewById<android.widget.Button>(R.id.remoteBrowseUp)
        val selectBtn = view.findViewById<android.widget.Button>(R.id.remoteBrowseSelect)
        list.layoutManager = LinearLayoutManager(activity)

        var browseRelPath = ""
        val baseNorm = normalizeBaseForBrowse(type, initialBasePath)

        fun draftConfig() = draftConfigProvider()

        fun playConfigId(): String = savedConfigId?.takeIf { it.isNotBlank() }
            ?: RemotePlayUri.PREVIEW_CONFIG_ID

        fun displayPath(): String {
            val rel = browseRelPath.trim('/')
            return if (rel.isEmpty()) baseNorm else joinRemotePath(baseNorm, rel)
        }

        fun listArg(): String = when (type) {
            "webdav" -> browseRelPath
            else -> displayPath()
        }

        fun entryRelPath(e: RemoteEntry): String = joinEntryPath(browseRelPath, e.path, e.name, e.directory)

        val builder = MvDialog.builder(activity)
            .setTitle(R.string.remote_browse_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)

        val dialog: AlertDialog = MvDialog.showStyled(builder, inputRoot = null)

        fun refreshUpEnabled() {
            upBtn.isEnabled = browseRelPath.isNotEmpty()
        }

        fun playVideo(e: RemoteEntry) {
            val rel = entryRelPath(e)
            val cfgId = playConfigId()
            if (cfgId == RemotePlayUri.PREVIEW_CONFIG_ID) {
                RemoteBrowsePreviewHolder.config = draftConfig()
            }
            val libPath = RemotePlayUri.libraryPath(cfgId, rel)
            activity.startActivity(PlayerActivity.intent(activity, libPath, e.name))
        }

        fun load() {
            pathTv.text = displayPath()
            refreshUpEnabled()
            activity.lifecycleScope.launch {
                val entries = withContext(Dispatchers.IO) {
                    runCatching {
                        RemoteClients.create(draftConfig()).list(listArg())
                    }.getOrElse { e ->
                        activity.runOnUiThread {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.remote_browse_failed, e.message ?: "?"),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        emptyList()
                    }
                }
                val sorted = entries.sortedWith(
                    compareBy<RemoteEntry> { !it.directory }.thenBy { it.name.lowercase() },
                )
                list.adapter = object : RecyclerView.Adapter<BrowseVH>() {
                    override fun getItemCount() = sorted.size
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BrowseVH {
                        val row = LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_remote_browse_dir, parent, false)
                        return BrowseVH(row)
                    }

                    override fun onBindViewHolder(h: BrowseVH, position: Int) {
                        val e = sorted[position]
                        val isVideo = !e.directory && RemoteMediaTypes.isVideoFileName(e.name)
                        h.name.text = e.name
                        val icon = when {
                            e.directory -> R.drawable.ic_folder_open
                            isVideo -> R.drawable.ic_remote_video
                            else -> R.drawable.ic_remote_file
                        }
                        h.name.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            AppCompatResources.getDrawable(h.name.context, icon),
                            null, null, null,
                        )
                        h.name.alpha = if (isVideo) 1f else if (e.directory) 1f else 0.85f
                        h.itemView.setOnClickListener {
                            when {
                                e.directory -> {
                                    browseRelPath = joinRelPath(browseRelPath, e.name)
                                    load()
                                }
                                isVideo -> playVideo(e)
                                else -> Toast.makeText(
                                    activity,
                                    e.name,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                if (sorted.isEmpty()) {
                    pathTv.text = "${displayPath()}\n${activity.getString(R.string.remote_browse_empty)}"
                }
            }
        }

        upBtn.setOnClickListener {
            browseRelPath = parentRelPath(browseRelPath)
            load()
        }
        selectBtn.setOnClickListener {
            onPathSelected(displayPath())
            dialog.dismiss()
        }

        load()
    }

    private class BrowseVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.remoteBrowseEntryName)
    }

    companion object {
        fun normalizeBaseForBrowse(type: String, base: String): String {
            var p = base.trim().ifBlank { defaultBaseForType(type) }
            if (type == "smb") {
                p = p.trimStart('/')
                if (p.isBlank()) p = "share"
                return p
            }
            if (!p.startsWith("/")) p = "/$p"
            return p.trimEnd('/').ifEmpty { "/" }
        }

        fun defaultBaseForType(type: String): String = when (type) {
            "webdav" -> "/dav"
            "ftp" -> "/"
            "smb" -> "/share"
            else -> "/dav"
        }

        fun joinRemotePath(base: String, rel: String): String {
            val b = base.trimEnd('/')
            val r = rel.trim('/')
            return if (r.isEmpty()) b.ifEmpty { "/" } else "$b/$r"
        }

        fun joinRelPath(current: String, childName: String): String {
            val c = current.trim('/')
            return if (c.isEmpty()) childName else "$c/$childName"
        }

        fun joinEntryPath(parent: String, entryPath: String, name: String, dir: Boolean): String {
            val p = when {
                entryPath.isNotBlank() -> entryPath.replace('\\', '/').trimStart('/')
                parent.isBlank() -> name
                else -> "${parent.trimEnd('/')}/$name"
            }
            return p.replace('\\', '/')
        }

        fun parentRelPath(current: String): String {
            val c = current.trim('/')
            if (c.isEmpty()) return ""
            return c.substringBeforeLast('/', "")
        }
    }
}