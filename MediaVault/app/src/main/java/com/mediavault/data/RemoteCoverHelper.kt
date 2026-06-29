package com.mediavault.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.mediavault.remote.RemoteClient
import com.mediavault.remote.RemoteConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 远程视频封面：优先同目录图片，否则下载片头一小段再抽帧。
 */
object RemoteCoverHelper {
    private const val MAX_SIDECAR_BYTES = 20L * 1024 * 1024
    private const val FRAME_HEAD_BYTES = 8L * 1024 * 1024
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")

    fun coverForVideo(
        context: Context,
        store: MediaStore,
        config: RemoteConfig,
        client: RemoteClient,
        relVideoPath: String,
        libraryPath: String,
        frameGate: RemoteFrameGate,
        coverFromFiles: Boolean = true,
        coverFromVideoFrame: Boolean = true,
    ): Pair<String?, String> {
        if (coverFromFiles) {
            val sidecar = cacheSidecarCover(context, store, config, client, relVideoPath)
            if (sidecar != null) return sidecar to "sidecar"
        }
        if (coverFromVideoFrame) {
            val frame = frameGate.withPermit {
                cacheFrameCover(context, store, config, client, relVideoPath)
            }
            if (frame != null) return frame to "frame"
        }
        return null to ""
    }

    private fun cacheSidecarCover(
        context: Context,
        store: MediaStore,
        config: RemoteConfig,
        client: RemoteClient,
        relVideoPath: String,
    ): String? {
        val norm = relVideoPath.replace('\\', '/').trimStart('/')
        val parent = norm.substringBeforeLast('/', "")
        val videoName = norm.substringAfterLast('/')
        val base = videoName.substringBeforeLast('.')
        val bases = listOf(base, videoName, "$base-poster", "$base-cover", "$base-thumb", "$base-thumbnail")
        val listPath = parent.ifBlank { "" }
        val entries = runCatching { client.list(listPath) }.getOrElse { emptyList() }
        for (e in entries) {
            if (e.directory) continue
            val name = e.name.ifBlank { e.path.substringAfterLast('/') }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in IMG_EXT) continue
            val stem = name.substringBeforeLast('.')
            if (bases.none { it.equals(stem, ignoreCase = true) }) continue
            if (e.size > MAX_SIDECAR_BYTES) continue
            val relImg = joinPath(parent, name)
            val key = "remote-image|${config.id}|$relVideoPath|$relImg|${e.size}"
            val dest = coverFile(store.coversDir, key, if (ext == "jpeg") "jpg" else ext)
            if (!dest.isFile || dest.length() == 0L) {
                runCatching {
                    client.openRead(relImg).use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                }.getOrNull() ?: continue
                if (!dest.isFile || dest.length() == 0L) continue
            }
            return dest.absolutePath
        }
        return null
    }

    private fun cacheFrameCover(
        context: Context,
        store: MediaStore,
        config: RemoteConfig,
        client: RemoteClient,
        relVideoPath: String,
    ): String? {
        val key = "remote-frame|${config.id}|$relVideoPath|$FRAME_HEAD_BYTES"
        val dest = coverFile(store.coversDir, key, "jpg")
        if (dest.isFile && dest.length() > 0) return dest.absolutePath
        val ext = relVideoPath.substringAfterLast('.', "mp4").lowercase()
        val tmp = File.createTempFile("mv-remote-frame-", ".$ext", context.cacheDir)
        try {
            if (!downloadHead(client, relVideoPath, tmp, FRAME_HEAD_BYTES)) return null
            val bmp = extractFrame(tmp) ?: return null
            FileOutputStream(dest).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bmp.recycle()
            return if (dest.isFile) dest.absolutePath else null
        } finally {
            tmp.delete()
        }
    }

    private fun downloadHead(client: RemoteClient, relPath: String, dest: File, maxBytes: Long): Boolean {
        return try {
            client.openRead(relPath, 0L, maxBytes).use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = maxBytes
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        left -= n.toLong()
                    }
                }
            }
            dest.isFile && dest.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun extractFrame(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun coverFile(dir: File, key: String, ext: String): File {
        val id = sha1(key)
        val safe = if (ext == "jpeg") "jpg" else ext
        return File(dir, "$id.$safe")
    }

    private fun joinPath(parent: String, name: String): String = when {
        parent.isBlank() -> name
        else -> "${parent.trimEnd('/')}/$name"
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}