package com.mediavault.remote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import androidx.media3.common.C
import java.io.IOException

class FtpClientImpl(private val cfg: RemoteConfig) : RemoteClient {
    private fun connect(): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = 30_000
        ftp.defaultTimeout = 120_000
        ftp.connect(cfg.host, cfg.port)
        if (!ftp.login(cfg.user, cfg.password)) {
            throw IOException("FTP 登录失败: ${ftp.replyString}")
        }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        return ftp
    }

    override fun testConnection(): String {
        connect().use { ftp ->
            ftp.logout()
            ftp.disconnect()
        }
        return "FTP 连接成功"
    }

    override fun fileSize(relativePath: String): Long {
        connect().use { ftp ->
            var path = relativePath.trim().replace('\\', '/')
            if (!path.startsWith("/")) path = "/$path"
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
        var path = relativePath.trim().replace('\\', '/')
        if (!path.startsWith("/")) path = "/$path"
        val raw = ftp.retrieveFileStream(path)
            ?: run {
                runCatching { ftp.logout(); ftp.disconnect() }
                throw IOException("FTP 无法打开: $path")
            }
        if (offset > 0) raw.skip(offset)
        val limited = if (length != C.LENGTH_UNSET.toLong() && length > 0) {
            RemoteLimitedInputStream(raw, length)
        } else {
            raw
        }
        return object : java.io.FilterInputStream(limited) {
            override fun close() {
                super.close()
                runCatching {
                    ftp.completePendingCommand()
                    ftp.logout()
                    ftp.disconnect()
                }
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val ftp = connect()
        try {
            var dir = path.ifBlank { cfg.basePath }
            if (!dir.startsWith("/")) dir = "/$dir"
            val files: Array<FTPFile> = ftp.listFiles(dir) ?: emptyArray()
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
            runCatching {
                logout()
                disconnect()
            }
        }
    }
}