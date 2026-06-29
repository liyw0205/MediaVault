package com.mediavault.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * TMDB API 响应磁盘缓存：`filesDir/mediavault/tmdb-cache/`。
 * 键为请求 URL 的 SHA-256，减轻重复刮削与手动搜索的流量。
 */
object TmdbDiskCache {
    private const val SUBDIR = "mediavault/tmdb-cache"
    private const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_FILES = 900

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.applicationContext.filesDir, SUBDIR).also { it.mkdirs() }
    }

    fun read(url: String): JSONObject? {
        val dir = cacheDir ?: return null
        val f = fileFor(dir, url)
        if (!f.isFile) return null
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > DISK_TTL_MS) {
            f.delete()
            return null
        }
        return runCatching {
            val text = f.readText(Charsets.UTF_8)
            if (text.isBlank()) return@runCatching null
            JSONObject(text)
        }.getOrNull()
    }

    fun write(url: String, json: JSONObject) {
        val dir = cacheDir ?: return
        val f = fileFor(dir, url)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(json.toString(), Charsets.UTF_8)
            f.setLastModified(System.currentTimeMillis())
            pruneIfNeeded(dir)
        }
    }

    fun clearAll() {
        val dir = cacheDir ?: return
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun entryCount(): Int = cacheDir?.listFiles()?.count { it.isFile } ?: 0

    private fun fileFor(dir: File, url: String): File {
        val hex = sha256Hex(url)
        return File(dir, "$hex.json")
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    private fun pruneIfNeeded(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES + 50)
            .forEach { it.delete() }
    }
}