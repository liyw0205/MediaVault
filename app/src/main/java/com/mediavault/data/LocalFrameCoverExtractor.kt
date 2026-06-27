package com.mediavault.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** 本地 content:// 视频：无同目录封面时从片头抽帧写入 covers/。 */
object LocalFrameCoverExtractor {
    private const val COPY_HEAD_BYTES = 12L * 1024 * 1024

    fun cacheFrameCover(
        context: Context,
        videoUri: Uri,
        videoPath: String,
        coversDir: File,
        frameGate: RemoteFrameGate,
    ): String? = frameGate.withPermit {
        val key = "local-frame|$videoPath"
        val dest = coverFile(coversDir, key, "jpg")
        if (dest.isFile && dest.length() > 0L) return@withPermit dest.absolutePath
        val bmp = extractFrameBitmap(context, videoUri) ?: return@withPermit null
        return@withPermit try {
            FileOutputStream(dest).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bmp.recycle()
            if (dest.isFile && dest.length() > 0L) dest.absolutePath else null
        } catch (_: Exception) {
            bmp.recycle()
            null
        }
    }

    private fun extractFrameBitmap(context: Context, videoUri: Uri): Bitmap? {
        extractWithFd(context, videoUri)?.let { return it }
        val ext = videoUri.lastPathSegment?.substringAfterLast('.', "mp4")?.lowercase() ?: "mp4"
        val tmp = File.createTempFile("mv-local-frame-", ".$ext", context.cacheDir)
        try {
            if (!copyHead(context, videoUri, tmp, COPY_HEAD_BYTES)) return null
            return extractFromPath(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun extractWithFd(context: Context, videoUri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(videoUri, "r") ?: return null
            r.setDataSource(pfd.fileDescriptor)
            frameAt(r)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun extractFromPath(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            frameAt(r)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun frameAt(r: MediaMetadataRetriever): Bitmap? =
        r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.frameAtTime

    private fun copyHead(context: Context, uri: Uri, dest: File, maxBytes: Long): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
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
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun coverFile(dir: File, key: String, ext: String): File {
        val id = sha1(key)
        return File(dir, "$id.$ext")
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}