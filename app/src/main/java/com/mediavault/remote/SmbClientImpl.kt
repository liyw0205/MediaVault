package com.mediavault.remote

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare

class SmbClientImpl(private val cfg: RemoteConfig) : RemoteClient {
    private val shareName: String
    private val rootOnShare: String

    init {
        val bp = cfg.basePath.trim().trimStart('\\', '/')
        val parts = bp.split('/', '\\').filter { it.isNotBlank() }
        shareName = parts.firstOrNull() ?: "share"
        rootOnShare = if (parts.size > 1) parts.drop(1).joinToString("\\") else ""
    }

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val client = SMBClient()
        val conn: Connection = client.connect(cfg.host)
        try {
            val auth = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
            val session: Session = conn.authenticate(auth)
            val share = session.connectShare(shareName) as DiskShare
            try {
                return block(share)
            } finally {
                share.close()
            }
        } finally {
            conn.close()
            client.close()
        }
    }

    override fun testConnection(): String {
        withShare { share ->
            share.list("")
        }
        return "SMB 连接成功 (share=$shareName)"
    }

    override fun list(path: String): List<RemoteEntry> {
        var p = path.ifBlank { rootOnShare }
        p = p.replace('/', '\\').trim('\\')
        if (rootOnShare.isNotBlank()) {
            p = if (p.isBlank()) rootOnShare else "$rootOnShare\\$p"
        }
        return withShare { share ->
            share.list(p).mapNotNull { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val full = if (p.isBlank()) name else "$p\\$name"
                val dir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                RemoteEntry(full.replace('\\', '/'), name, dir, info.endOfFile)
            }
        }
    }
}