package com.mediavault.remote

import java.io.EOFException
import java.io.InputStream
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
        var conn: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            conn = client.connect(cfg.host, cfg.port)
            val auth = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
            session = conn.authenticate(auth)
            share = session.connectShare(shareName) as DiskShare
            return block(share)
        } finally {
            closeSmbResources(share = share, session = session, conn = conn, client = client)
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
        if (length == 0L) return java.io.ByteArrayInputStream(ByteArray(0))
        val p = smbPath(relativePath)
        val client = SMBClient()
        var conn: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        return try {
            conn = client.connect(cfg.host, cfg.port)
            val auth = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
            session = conn.authenticate(auth)
            share = session.connectShare(shareName) as DiskShare
            file = share.openFile(
                p,
                java.util.EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val raw = file.inputStream
            if (offset > 0) {
                skipFully(raw, offset)
            }
            val limited = if (length != C.LENGTH_UNSET.toLong() && length > 0) {
                RemoteLimitedInputStream(raw, length)
            } else {
                raw
            }
            object : java.io.FilterInputStream(limited) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        closeSmbResources(file, share, session, conn, client)
                    }
                }
            }
        } catch (t: Throwable) {
            closeSmbResources(file, share, session, conn, client)
            throw t
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

    private fun skipFully(input: InputStream, offset: Long) {
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) {
                throw EOFException("SMB 无法跳转到指定位置：$offset")
            }
            left--
        }
    }

    private fun closeSmbResources(
        file: com.hierynomus.smbj.share.File? = null,
        share: DiskShare? = null,
        session: Session? = null,
        conn: Connection? = null,
        client: SMBClient? = null,
    ) {
        runCatching { file?.close() }
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { conn?.close() }
        runCatching { client?.close() }
    }
}
