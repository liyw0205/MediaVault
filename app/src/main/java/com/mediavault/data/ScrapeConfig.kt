package com.mediavault.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ScrapeConfig {
    const val DEFAULT_THREADS = 10
    const val MIN_THREADS = 1
    const val MAX_THREADS = 32

    private fun file(context: Context) = File(context.filesDir, "mediavault/scrape-config.json")

    fun readThreadCount(context: Context): Int {
        val f = file(context)
        if (!f.isFile) return DEFAULT_THREADS
        return runCatching {
            JSONObject(f.readText()).optInt("threadCount", DEFAULT_THREADS)
        }.getOrDefault(DEFAULT_THREADS).coerceIn(MIN_THREADS, MAX_THREADS)
    }

    fun writeThreadCount(context: Context, count: Int) {
        val n = count.coerceIn(MIN_THREADS, MAX_THREADS)
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("threadCount", n).toString(2))
    }
}