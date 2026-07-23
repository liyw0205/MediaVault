package com.mediavault.playback

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mediavault.R
import com.mediavault.data.MediaItem
import com.mediavault.remote.RemotePath
import java.io.File

/** 用系统选择器把视频交给其他 App 播放。 */
object ExternalOpen {
    fun open(context: Context, item: MediaItem): Boolean = openPath(context, item.path)

    fun openPath(context: Context, path: String): Boolean {
        if (path.isBlank()) {
            Toast.makeText(context, R.string.open_external_unavailable, Toast.LENGTH_SHORT).show()
            return false
        }
        if (RemotePath.isRemote(path)) {
            Toast.makeText(context, R.string.open_external_remote_unsupported, Toast.LENGTH_LONG).show()
            return false
        }
        val uri = resolveShareUri(context, path) ?: run {
            Toast.makeText(context, R.string.open_external_unavailable, Toast.LENGTH_SHORT).show()
            return false
        }
        val mime = guessVideoMime(path)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分播放器只从 ClipData 取可授权 URI
            clipData = ClipData.newUri(context.contentResolver, "video", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(
                Intent.createChooser(view, context.getString(R.string.open_external_chooser)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.open_external_no_app, Toast.LENGTH_SHORT).show()
            false
        } catch (e: SecurityException) {
            Toast.makeText(
                context,
                e.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.open_external_unavailable),
                Toast.LENGTH_LONG,
            ).show()
            false
        }
    }

    private fun resolveShareUri(context: Context, path: String): Uri? {
        if (path.startsWith("content://")) return Uri.parse(path)
        if (path.startsWith("file://")) {
            val file = File(Uri.parse(path).path ?: return null)
            return if (file.isFile) fileProviderUri(context, file) else Uri.parse(path)
        }
        val file = File(path)
        if (file.isAbsolute && file.isFile) return fileProviderUri(context, file)
        return null
    }

    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun guessVideoMime(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast(':')
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "video/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
    }
}
