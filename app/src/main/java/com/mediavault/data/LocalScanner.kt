package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LocalScanner {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg",
    )

    fun isContentLibraryPath(path: String): Boolean =
        path.startsWith("content://")

    fun scanTreeUrisBatched(
        context: Context,
        store: MediaStore,
        rebuild: Boolean,
        batchSize: Int,
        shouldCancel: () -> Boolean,
        onFile: (MediaItem) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val uris = store.readLocalRootUris()
        if (uris.isEmpty()) throw IllegalStateException("请先添加本地媒体根目录")
        onStatus("扫描 ${uris.size} 个根目录…")
        for (uriStr in uris) {
            if (shouldCancel()) return
            val uri = Uri.parse(uriStr)
            val root = DocumentFile.fromTreeUri(context, uri) ?: continue
            walk(
                context, store, root, null, root.name ?: "root", rebuild, shouldCancel, onFile, onStatus,
            )
        }
        onStatus("目录遍历结束")
    }

    private fun walk(
        context: Context,
        store: MediaStore,
        dir: DocumentFile,
        parent: DocumentFile?,
        prefix: String,
        rebuild: Boolean,
        shouldCancel: () -> Boolean,
        onFile: (MediaItem) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        for (child in dir.listFiles()) {
            if (shouldCancel()) return
            if (child.isDirectory) {
                walk(
                    context, store, child, dir, "$prefix/${child.name}", rebuild, shouldCancel, onFile, onStatus,
                )
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in VIDEO_EXT) continue
                val path = child.uri.toString()
                if (!rebuild && store.hasScrapeRecord(path)) continue

                val folder = dir.name ?: ""
                val sidecar = SidecarScanner.scanAroundVideo(
                    context, dir, parent, name, path, store.coversDir,
                )
                val nfoObj = if (!sidecar.nfoXml.isNullOrBlank()) NfoParser.parseXml(sidecar.nfoXml) else JSONObject()

                val clean = name.substringBeforeLast('.')
                var title = clean
                val titleCn = nfoObj.optString("title_cn", "").trim()
                val nfoTitle = nfoObj.optString("title", "").trim()
                title = when {
                    titleCn.isNotBlank() -> titleCn
                    nfoTitle.isNotBlank() -> nfoTitle
                    else -> clean
                }

                var year = SidecarScanner.yearFromName(name)
                val nfoYear = nfoObj.optString("year", "").trim()
                if (nfoYear.isNotBlank()) year = nfoYear

                val (season, episode) = SidecarScanner.seasonEpisodeFromName(name)
                var collection = folder.trim()
                val setName = nfoObj.optString("set_name", "").trim()
                val nfoColl = nfoObj.optString("collection", "").trim()
                var collectionKey = ""
                when {
                    setName.isNotBlank() -> {
                        collection = setName
                        collectionKey = setName
                    }
                    nfoColl.isNotBlank() -> {
                        collection = nfoColl
                        collectionKey = nfoColl
                    }
                    else -> collection = cleanFolderName(dir.uri.toString())
                }

                val tags = TagHarvest.harvest(name, folder, nfoObj)
                val genres = mutableListOf<String>()
                val ga = nfoObj.optJSONArray("genres_xml")
                if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optString(i))

                val plot = nfoObj.optString("plot", "").trim()

                val o = JSONObject()
                o.put("path", path)
                o.put("title", title)
                o.put("clean_title", clean)
                o.put("size", child.length())
                o.put("modified", fmtDate(child.lastModified()))
                o.put("folder", folder)
                o.put("collection", collection)
                o.put("collection_key_name", collectionKey)
                o.put("year", year)
                o.put("season", season.ifBlank { nfoObj.optString("season", "") })
                o.put("episode", episode.ifBlank { nfoObj.optString("episode", "") })
                o.put("plot", plot)
                o.put("tags", TagHarvest.toJsonArray(tags))
                o.put("genres", JSONArray().also { arr -> genres.forEach { arr.put(it) } })
                if (nfoObj.length() > 0) o.put("nfo", nfoObj)
                if (sidecar.coverUri != null) o.put("cover_uri", sidecar.coverUri)
                if (sidecar.coverLocal != null) {
                    o.put("cover_local", sidecar.coverLocal)
                    o.put("cover_source", "sidecar")
                }
                if (sidecar.subtitles.isNotEmpty()) {
                    o.put("subtitles", JSONArray().also { arr -> sidecar.subtitles.forEach { arr.put(it) } })
                }
                if (sidecar.siblingNames.isNotEmpty()) {
                    o.put("sidecar_files", JSONArray().also { arr ->
                        sidecar.siblingNames.take(32).forEach { arr.put(it) }
                    })
                }
                for (key in listOf(
                    "originaltitle", "title_cn", "title_jp", "title_rm", "sorttitle", "tagline",
                    "premiered", "releasedate", "studio", "country", "director", "writer",
                    "mpaa", "rating", "runtime", "status",
                )) {
                    val v = nfoObj.optString(key, "").trim()
                    if (v.isNotBlank()) o.put(key, v)
                }

                val item = MediaItem.fromJson(o)
                store.recordScrapedPath(path)
                onFile(item)
                onStatus(name)
            }
        }
    }

    private fun cleanFolderName(dirUri: String): String {
        val seg = dirUri.substringAfterLast('%').substringAfterLast('/')
        return seg.ifBlank { "未分组" }
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}