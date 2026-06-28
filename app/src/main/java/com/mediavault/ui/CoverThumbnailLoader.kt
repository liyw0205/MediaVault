package com.mediavault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.mediavault.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 列表封面：后台采样解码 + 内存缓存，避免 bind 里 decodeFile 卡主线程 */
object CoverThumbnailLoader {
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val jobs = ConcurrentHashMap<ImageView, Job>()

    fun load(
        scope: CoroutineScope,
        imageView: ImageView,
        localPath: String?,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ) {
        jobs[imageView]?.cancel()
        imageView.setTag(R.id.cover_load_path, localPath)

        if (localPath.isNullOrBlank() || !File(localPath).isFile) {
            imageView.setImageDrawable(null)
            imageView.setTag(R.id.cover_load_path, null)
            return
        }

        cache.get(localPath)?.let { bmp ->
            if (!bmp.isRecycled) {
                imageView.setImageBitmap(bmp)
                return
            }
            cache.remove(localPath)
        }

        val w = targetWidthPx.coerceAtLeast(64)
        val h = targetHeightPx.coerceAtLeast(64)
        val job = scope.launch(Dispatchers.Main.immediate) {
            val bmp = withContext(Dispatchers.IO) {
                decodeSampled(localPath, w, h)
            }
            if (imageView.getTag(R.id.cover_load_path) != localPath) return@launch
            if (bmp != null) {
                cache.put(localPath, bmp)
                imageView.setImageBitmap(bmp)
            } else {
                imageView.setImageDrawable(null)
            }
        }
        jobs[imageView] = job
        job.invokeOnCompletion { jobs.remove(imageView, job) }
    }

    fun cancel(imageView: ImageView) {
        jobs.remove(imageView)?.cancel()
        imageView.setTag(R.id.cover_load_path, null)
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (height, width) = opts.outHeight to opts.outWidth
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            var halfH = height / 2
            var halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}