package com.mediavault.data

import android.content.Context
import org.json.JSONObject
import java.io.File

object ScrapeConfig {
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

    fun readThreadCount(context: Context): Int {
        return readJson(context).optInt("threadCount", DEFAULT_THREADS)
            .coerceIn(MIN_THREADS, MAX_THREADS)
    }

    fun writeThreadCount(context: Context, count: Int) {
        val n = count.coerceIn(MIN_THREADS, MAX_THREADS)
        val obj = readJson(context)
        obj.put("threadCount", n)
        writeJson(context, obj)
    }

    fun readRemoteFrameConcurrency(context: Context): Int {
        return readJson(context).optInt("remoteFrameConcurrency", DEFAULT_REMOTE_FRAME)
            .coerceIn(MIN_REMOTE_FRAME, MAX_REMOTE_FRAME)
    }

    fun writeRemoteFrameConcurrency(context: Context, count: Int) {
        val n = count.coerceIn(MIN_REMOTE_FRAME, MAX_REMOTE_FRAME)
        val obj = readJson(context)
        obj.put("remoteFrameConcurrency", n)
        writeJson(context, obj)
    }
}