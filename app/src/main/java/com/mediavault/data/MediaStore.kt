package com.mediavault.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStore(private val context: Context) {
    private val baseDir: File
        get() = File(context.filesDir, "mediavault").also { it.mkdirs() }

    val libraryFile: File get() = File(baseDir, "library.json")
    val rootsListFile: File get() = File(baseDir, "roots.list")
    val remotesFile: File get() = File(baseDir, "remotes.json")
    val scrapeRecordFile: File get() = File(baseDir, "scrape-record.tsv")
    val coversDir: File get() = File(baseDir, "covers").also { it.mkdirs() }

    fun readLibraryText(): String? {
        if (!libraryFile.isFile) return null
        return libraryFile.readText(Charsets.UTF_8)
    }

    fun writeLibraryFromImport(uri: Uri): Result<Int> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            libraryFile.outputStream().use { out -> input.copyTo(out) }
        } ?: error("无法打开文件")
        val lib = MediaLibrary.parse(libraryFile.readText(), libraryFile.absolutePath)
        lib.items.size
    }

    fun writeLibraryJson(items: List<MediaItem>): Result<Unit> = runCatching {
        val arr = JSONArray()
        for (item in items) arr.put(item.raw)
        val root = JSONObject()
        root.put("ok", true)
        root.put("items", arr)
        root.put("updated", nowText())
        libraryFile.writeText(root.toString(), Charsets.UTF_8)
    }

    fun readLocalRootUris(): List<String> {
        if (!rootsListFile.isFile) return emptyList()
        return rootsListFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    fun writeLocalRootUris(lines: List<String>) {
        rootsListFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    fun appendLocalRootUri(uri: String) {
        val cur = readLocalRootUris().toMutableList()
        if (!cur.contains(uri)) {
            cur.add(uri)
            writeLocalRootUris(cur)
        }
    }

    fun readRemotesJsonText(): String {
        if (!remotesFile.isFile) return "[]"
        return remotesFile.readText(Charsets.UTF_8)
    }

    fun writeRemotesJsonText(text: String) {
        JSONArray(text) // validate
        remotesFile.writeText(text, Charsets.UTF_8)
    }

    fun readNfoTitleFromUri(uri: Uri): String {
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri) ?: return ""
        val nfo = doc.findFile("movie.nfo") ?: doc.findFile("tvshow.nfo") ?: return ""
        if (!nfo.isFile) return ""
        return context.contentResolver.openInputStream(nfo.uri)?.use { input ->
            val xml = input.bufferedReader().readText()
            Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim() ?: ""
        } ?: ""
    }

    fun hasScrapeRecord(path: String): Boolean {
        if (!scrapeRecordFile.isFile) return false
        return scrapeRecordFile.readLines().any { it.trim() == path }
    }

    fun recordScrapedPath(path: String) {
        if (hasScrapeRecord(path)) return
        scrapeRecordFile.appendText("$path\n", Charsets.UTF_8)
    }

    fun clearScrapeRecord() {
        if (scrapeRecordFile.isFile) scrapeRecordFile.delete()
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}