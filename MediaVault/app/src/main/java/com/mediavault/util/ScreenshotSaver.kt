package com.mediavault.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mediavault.R
import java.io.File
import java.io.FileOutputStream

object ScreenshotSaver {
    private const val ALBUM_FOLDER = "MediaVault"

    /**
     * 保存 PNG 到公有 Pictures/MediaVault，相册可见。
     * @return 用于 Toast 的简短说明
     */
    fun savePng(context: Context, bitmap: Bitmap): Result<String> {
        val name = "MediaVault_${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, name)
        } else {
            saveLegacyPublicPictures(context, bitmap, name)
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, name: String): Result<String> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException("MediaStore insert failed"))
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)) {
                    throw IllegalStateException("compress failed")
                }
            } ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Result.success(
                context.getString(R.string.screenshot_saved_pictures, ALBUM_FOLDER, name),
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return Result.failure(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyPublicPictures(context: Context, bitmap: Bitmap, name: String): Result<String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_FOLDER,
        )
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("cannot create ${dir.absolutePath}"))
        }
        val file = File(dir, name)
        return try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)) {
                    throw IllegalStateException("compress failed")
                }
            }
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/png"),
                null,
            )
            Result.success(context.getString(R.string.screenshot_saved, file.absolutePath))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}