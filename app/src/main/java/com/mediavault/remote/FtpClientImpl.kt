package com.mediavault.remote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import androidx.media3.common.C
import java.io.IOException

class FtpClientImpl(private val cfg: RemoteConfig) : RemoteClient {
    private fun connect(): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = 30_000
        ftp.defaultTimeout = 120_000
        try {
            ftp.connect(cfg.host, cfg.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw IOException("FTP 连接失败: ${ftp.replyString?.trim()}")
            }
            if (!ftp.login(cfg.user, cfg.password)) {
                throw IOException("FTP 登录失败: ${ftp.replyString}")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            return ftp
        } catch (t: Throwable) {
            closeFtp(ftp)
            throw t
        }
    }

    override fun testConnection(): String {
        val entries = list("")
        val dirs = entries.count { it.directory }
        return "FTP 连接成功，条目 ${entries.size}（目录 $dirs）"
    }

    override fun fileSize(relativePath: String): Long {
        connect().use { ftp ->
            val path = ftpPath(relativePath)
            val parent = path.substringBeforeLast('/', "/").ifBlank { "/" }
            val name = path.substringAfterLast('/')
            val files = ftp.listFiles(parent) ?: return C.LENGTH_UNSET.toLong()
            return files.firstOrNull { it.name == name }?.size ?: C.LENGTH_UNSET.toLong()
        }
    }

    override fun openRead(
        relativePath: String,
        offset: Long,
        length: Long,
    ): java.io.InputStream {
        val ftp = connect()
        val path = ftpPath(relativePath)
        if (offset > 0) {
            ftp.setRestartOffset(offset)
        }
        var raw = ftp.retrieveFileStream(path)
        if (raw == null && offset > 0) {
            closeFtp(ftp)
            val ftp2 = connect()
            ftp2.sendCommand("REST", offset.toString())
            if (FTPReply.isPositiveIntermediate(ftp2.replyCode)) {
                raw = ftp2.retrieveFileStream(path)
                if (raw != null) {
                    return wrapFtpStream(ftp2, raw, length)
                }
            }
            closeFtp(ftp2)
            throw IOException("FTP 不支持断点续传（拖动到未缓存位置）：REST $offset")
        }
        if (raw == null) {
            val code = ftp.replyCode
            val reply = ftp.replyString?.trim().orEmpty()
            closeFtp(ftp)
            if (offset > 0 && (code == 501 || code == 502 || code == 550 ||
                    (reply.contains("REST", ignoreCase = true) && reply.contains("not", ignoreCase = true)))
            ) {
                throw IOException("FTP 不支持断点续传（拖动到未缓存位置）：服务端拒绝 REST $offset")
            }
            throw IOException("FTP 无法打开: $path")
        }
        return wrapFtpStream(ftp, raw, length)
    }

    private fun wrapFtpStream(
        ftp: FTPClient,
        raw: java.io.InputStream,
        length: Long,
    ): java.io.InputStream {
        val limited = if (length != C.LENGTH_UNSET.toLong() && length > 0) {
            RemoteLimitedInputStream(raw, length)
        } else {
            raw
        }
        return object : java.io.FilterInputStream(limited) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { ftp.completePendingCommand() }
                    closeFtp(ftp)
                }
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val ftp = connect()
        try {
            val dir = ftpPath(path)
            val files: Array<FTPFile> = ftp.listFiles(dir) ?: emptyArray()
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw IOException("FTP 列目录失败: $dir ${ftp.replyString?.trim().orEmpty()}")
            }
            return files.mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                val full = (dir.trimEnd('/') + "/" + name).replace("//", "/")
                RemoteEntry(full, name, f.isDirectory, f.size)
            }
        } finally {
            runCatching {
                ftp.logout()
                ftp.disconnect()
            }
        }
    }

    private inline fun <R> FTPClient.use(block: (FTPClient) -> R): R {
        return try {
            block(this)
        } finally {
            closeFtp(this)
        }
    }

    private fun ftpPath(path: String): String {
        val rel = path.trim().replace('\\', '/').trim('/')
        val base = cfg.basePath.trim().replace('\\', '/').trim('/').ifBlank { "" }
        return when {
            rel.isBlank() -> if (base.isBlank()) "/" else "/$base"
            path.trim().replace('\\', '/').startsWith("/") -> "/$rel"
            base.isBlank() -> "/$rel"
            rel == base || rel.startsWith("$base/") -> "/$rel"
            else -> "/$base/$rel"
        }.replace(Regex("/{2,}"), "/")
    }

    private fun closeFtp(ftp: FTPClient) {
        runCatching {
            if (ftp.isConnected) {
                runCatching { ftp.logout() }
                ftp.disconnect()
            }
        }
    }
}
