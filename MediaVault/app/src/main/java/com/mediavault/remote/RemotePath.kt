package com.mediavault.remote

import com.mediavault.data.MediaStore
import org.json.JSONObject

/** 库内 path：`remote|<configId>|<posixPath>` */
object RemotePath {
    const val PREFIX = "remote|"

    fun encode(configId: String, remotePath: String): String {
        val p = remotePath.trim().replace('\\', '/').trimStart('/')
        return "$PREFIX$configId|$p"
    }

    fun isRemote(path: String): Boolean = path.startsWith(PREFIX)

    fun parse(path: String): Parsed? {
        if (!isRemote(path)) return null
        val rest = path.removePrefix(PREFIX)
        val bar = rest.indexOf('|')
        if (bar <= 0) return null
        val id = rest.substring(0, bar)
        val rel = rest.substring(bar + 1)
        return Parsed(id, rel)
    }

    data class Parsed(val configId: String, val relativePath: String)

    fun configFor(store: MediaStore, path: String): RemoteConfig? {
        val p = parse(path) ?: return null
        return store.readRemotesList().find { it.id == p.configId }
    }
}