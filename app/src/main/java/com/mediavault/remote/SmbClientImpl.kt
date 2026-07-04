package com.mediavault.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import androidx.media3.common.C

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
        val entries = list("")
        return "SMB 连接成功 (share=$shareName，条目 ${entries.size})"
    }

    override fun fileSize(relativePath: String): Long = withShare { share ->
        val p = smbPath(relativePath)
        runCatching {
            share.openFile(
                p,
                java.util.EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.fileInformation.standardInformation.endOfFile }
        }.getOrDefault(C.LENGTH_UNSET.toLong())
    }

    private fun smbPath(relativePath: String): String {
        val rel = normalizeRemoteRelative(relativePath)
        return when {
            rootOnShare.isBlank() -> rel
            rel.isBlank() -> rootOnShare
            else -> "$rootOnShare\\$rel"
        }
    }

    override fun openRead(
        relativePath: String,
        offset: Long,
        length: Long,
    ): java.io.InputStream {
        val p = smbPath(relativePath)
        val client = SMBClient()
        val conn = client.connect(cfg.host)
        val auth = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
        val session = conn.authenticate(auth)
        val share = session.connectShare(shareName) as DiskShare
        val file = share.openFile(
            p,
            java.util.EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val raw = file.inputStream
        if (offset > 0) {
            var left = offset
            while (left > 0) {
                val skipped = raw.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
        }
        val limited = if (length != C.LENGTH_UNSET.toLong() && length > 0) {
            RemoteLimitedInputStream(raw, length)
        } else {
            raw
        }
        return object : java.io.FilterInputStream(limited) {
            override fun close() {
                super.close()
                runCatching { file.close() }
                runCatching { share.close() }
                runCatching { session.close() }
                runCatching { conn.close() }
                runCatching { client.close() }
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val rel = normalizeRemoteRelative(path)
        val p = when {
            rootOnShare.isBlank() -> rel
            rel.isBlank() -> rootOnShare
            else -> "$rootOnShare\\$rel"
        }
        return withShare { share ->
            share.list(p).mapNotNull { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val full = if (rel.isBlank()) name else "$rel\\$name"
                val dir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()) != 0L
                RemoteEntry(full.replace('\\', '/'), name, dir, info.endOfFile)
            }
        }
    }

    private fun normalizeRemoteRelative(path: String): String {
        var rel = path.replace('/', '\\').trim('\\')
        if (rel.equals(shareName, ignoreCase = true)) {
            rel = ""
        } else if (rel.startsWith("$shareName\\", ignoreCase = true)) {
            rel = rel.substring(shareName.length + 1)
        }
        if (rootOnShare.isNotBlank()) {
            if (rel.equals(rootOnShare, ignoreCase = true)) return ""
            if (rel.startsWith("$rootOnShare\\", ignoreCase = true)) {
                return rel.substring(rootOnShare.length + 1)
            }
        }
        return rel
    }
}
