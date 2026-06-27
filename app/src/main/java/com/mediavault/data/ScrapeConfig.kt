package com.mediavault.data

import android.content.Context
import org.json.JSONObject
import java.io.File

object ScrapeConfig {
    const val MODE_LOCAL = "local"
    const val DEFAULT_THREADS = 10
    const val MIN_THREADS = 1
    const val MAX_THREADS = 32

    const val DEFAULT_REMOTE_FRAME = 2
    const val MIN_REMOTE_FRAME = 1
    const val MAX_REMOTE_FRAME = 5

    private fun file(context: Context) = File(context.filesDir, "mediavault/scrape-config.json")

    private fun readJson(context: Context): JSONObject {
        val f = file(context)
        if (!f.isFile) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeJson(context: Context, obj: JSONObject) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(obj.toString(2))
    }

    fun readSettings(context: Context): ScrapeSettings {
        val j = readJson(context)
        return ScrapeSettings(
            scrapeMode = j.optString("scrapeMode", MODE_LOCAL).ifBlank { MODE_LOCAL },
            threadCount = j.optInt("threadCount", DEFAULT_THREADS),
            remoteFrameConcurrency = j.optInt("remoteFrameConcurrency", DEFAULT_REMOTE_FRAME),
            coverFromFiles = j.optBoolean("coverFromFiles", true),
            coverFromVideoFrame = j.optBoolean("coverFromVideoFrame", true),
            metadataFromNfo = j.optBoolean("metadataFromNfo", true),
            metadataFromFilename = j.optBoolean("metadataFromFilename", true),
            scanSidecarSubtitles = j.optBoolean("scanSidecarSubtitles", true),
            remoteCacheMaxBytesPerFile = j.optLong("remoteCacheMaxBytesPerFile", 512L * 1024 * 1024),
            remoteCacheMaxTotalBytes = j.optLong("remoteCacheMaxTotalBytes", 2L * 1024 * 1024 * 1024),
        ).normalized()
    }

    fun writeSettings(context: Context, settings: ScrapeSettings) {
        val s = settings.normalized()
        val obj = readJson(context)
        obj.put("scrapeMode", s.scrapeMode)
        obj.put("threadCount", s.threadCount)
        obj.put("remoteFrameConcurrency", s.remoteFrameConcurrency)
        obj.put("coverFromFiles", s.coverFromFiles)
        obj.put("coverFromVideoFrame", s.coverFromVideoFrame)
        obj.put("metadataFromNfo", s.metadataFromNfo)
        obj.put("metadataFromFilename", s.metadataFromFilename)
        obj.put("scanSidecarSubtitles", s.scanSidecarSubtitles)
        obj.put("remoteCacheMaxBytesPerFile", s.remoteCacheMaxBytesPerFile)
        obj.put("remoteCacheMaxTotalBytes", s.remoteCacheMaxTotalBytes)
        writeJson(context, obj)
    }

    fun readThreadCount(context: Context): Int = readSettings(context).threadCount

    fun writeThreadCount(context: Context, count: Int) {
        writeSettings(context, readSettings(context).copy(threadCount = count))
    }

    fun readRemoteFrameConcurrency(context: Context): Int =
        readSettings(context).remoteFrameConcurrency

    fun writeRemoteFrameConcurrency(context: Context, count: Int) {
        writeSettings(context, readSettings(context).copy(remoteFrameConcurrency = count))
    }
}

data class ScrapeSettings(
    val scrapeMode: String = ScrapeConfig.MODE_LOCAL,
    val threadCount: Int = ScrapeConfig.DEFAULT_THREADS,
    val remoteFrameConcurrency: Int = ScrapeConfig.DEFAULT_REMOTE_FRAME,
    val coverFromFiles: Boolean = true,
    val coverFromVideoFrame: Boolean = true,
    val metadataFromNfo: Boolean = true,
    val metadataFromFilename: Boolean = true,
    val scanSidecarSubtitles: Boolean = true,
    val remoteCacheMaxBytesPerFile: Long = 512L * 1024 * 1024,
    val remoteCacheMaxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    fun normalized(): ScrapeSettings = copy(
        scrapeMode = if (scrapeMode == ScrapeConfig.MODE_LOCAL) ScrapeConfig.MODE_LOCAL else ScrapeConfig.MODE_LOCAL,
        threadCount = threadCount.coerceIn(ScrapeConfig.MIN_THREADS, ScrapeConfig.MAX_THREADS),
        remoteFrameConcurrency = remoteFrameConcurrency.coerceIn(
            ScrapeConfig.MIN_REMOTE_FRAME,
            ScrapeConfig.MAX_REMOTE_FRAME,
        ),
        remoteCacheMaxBytesPerFile = remoteCacheMaxBytesPerFile.coerceIn(
            64L * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
        ),
        remoteCacheMaxTotalBytes = remoteCacheMaxTotalBytes.coerceIn(
            256L * 1024 * 1024,
            16L * 1024 * 1024 * 1024,
        ).coerceAtLeast(remoteCacheMaxBytesPerFile),
    )
}