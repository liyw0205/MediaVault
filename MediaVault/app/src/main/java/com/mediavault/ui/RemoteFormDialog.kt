package com.mediavault.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mediavault.R
import com.mediavault.remote.RemoteConfig
import java.util.UUID

object RemoteFormDialog {
    fun show(
        activity: AppCompatActivity,
        type: String,
        existing: RemoteConfig?,
        onSaved: (RemoteConfig) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_remote_form, null)
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
        baseLayout.setEndIconOnClickListener {
            val h = host.text?.toString()?.trim().orEmpty()
            val p = port.text?.toString()?.toIntOrNull() ?: defaultPort.toInt()
            val u = user.text?.toString().orEmpty()
            val pw = password.text?.toString().orEmpty()
            val baseNorm = RemoteBrowseDialogHelper.normalizeBaseForBrowse(
                type,
                base.text?.toString()?.trim().orEmpty(),
            )
            RemoteBrowseDialogHelper(
                activity = activity,
                draftConfigProvider = {
                    RemoteConfig(
                        id = existing?.id ?: "browse",
                        type = type,
                        host = h,
                        port = p,
                        user = u,
                        password = pw,
                        basePath = baseNorm,
                        name = "browse",
                    )
                },
                onPathSelected = { chosen -> base.setText(chosen) },
            ).show(
                type = type,
                host = h,
                port = p,
                user = u,
                password = pw,
                initialBasePath = base.text?.toString()?.trim().orEmpty(),
                savedConfigId = existing?.id,
            )
        }
        val title = if (existing == null) {
            activity.getString(R.string.remote_dialog_add, type.uppercase())
        } else {
            activity.getString(R.string.remote_dialog_edit)
        }
        MvDialog.showStyled(
            MvDialog.builder(activity)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ ->
                    val h = host.text?.toString()?.trim().orEmpty()
                    if (h.isEmpty()) {
                        Toast.makeText(activity, R.string.settings_need_host, Toast.LENGTH_SHORT).show()
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
                    onSaved(cfg)
                },
            inputRoot = view,
        )
    }
}