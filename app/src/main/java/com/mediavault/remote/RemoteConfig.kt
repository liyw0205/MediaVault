package com.mediavault.remote

import org.json.JSONArray
import org.json.JSONObject

data class RemoteConfig(
    val id: String,
    val type: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val basePath: String,
    val name: String,
) {
    companion object {
        fun fromJson(o: JSONObject): RemoteConfig = RemoteConfig(
            id = o.optString("id", o.optString("name", "remote")),
            type = o.optString("type", "webdav").lowercase(),
            host = o.optString("host", ""),
            port = o.optInt("port", when (o.optString("type")) {
                "ftp" -> 21
                "smb" -> 445
                else -> 443
            }),
            user = o.optString("user", ""),
            password = o.optString("password", ""),
            basePath = o.optString("basePath", "").ifBlank {
                when (o.optString("type", "webdav").lowercase()) {
                    "ftp" -> "/"
                    "smb" -> "/share"
                    else -> "/dav"
                }
            },
            name = o.optString("name", o.optString("id", "remote")),
        )

        fun listFromJson(text: String): List<RemoteConfig> {
            val arr = JSONArray(text)
            val out = mutableListOf<RemoteConfig>()
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }
    }
}

data class RemoteEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
)

interface RemoteClient {
    fun list(path: String): List<RemoteEntry>
    fun testConnection(): String
    /** 从 offset 字节处读取远程文件流（用于播放） */
    fun openRead(relativePath: String, offset: Long = 0L): java.io.InputStream
}