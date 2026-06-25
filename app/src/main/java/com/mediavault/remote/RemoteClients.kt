package com.mediavault.remote

object RemoteClients {
    fun create(cfg: RemoteConfig): RemoteClient = when (cfg.type) {
        "ftp" -> FtpClientImpl(cfg)
        "smb" -> SmbClientImpl(cfg)
        else -> WebDavClient(cfg)
    }

    fun firstWebDav(text: String): RemoteConfig? =
        RemoteConfig.listFromJson(text).firstOrNull { it.type == "webdav" || it.type.isBlank() }
}