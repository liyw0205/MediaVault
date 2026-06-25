package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object LocalScanner {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg",
    )

    data class VideoWork(
        val dir: DocumentFile,
        val parent: DocumentFile?,
        val videoFile: DocumentFile,
        val name: String,
        val path: String,
        val folder: String,
    )

    fun isContentLibraryPath(path: String): Boolean =
        path.startsWith("content://")

    fun scanTreeUrisParallel(
        context: Context,
        store: MediaStore,
        rebuild: Boolean,
        threadCount: Int,
        rootUrisFilter: List<String>? = null,
        shouldCancel: () -> Boolean,
        onFile: (MediaItem) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        var uris = store.readLocalRootUris()
        if (!rootUrisFilter.isNullOrEmpty()) {
            uris = uris.filter { it in rootUrisFilter }
        }
        if (uris.isEmpty()) throw IllegalStateException("请先添加本地媒体根目录")
        val workers = threadCount.coerceIn(ScrapeConfig.MIN_THREADS, ScrapeConfig.MAX_THREADS)
        onStatus("收集待扫视频（${workers} 线程）…")
        val queue = ConcurrentLinkedQueue<VideoWork>()
        for (uriStr in uris) {
            if (shouldCancel()) return
            val uri = Uri.parse(uriStr)
            val root = DocumentFile.fromTreeUri(context, uri) ?: continue
            collectVideos(store, root, null, root.name ?: "root", rebuild, shouldCancel, queue)
        }
        val total = queue.size
        if (total == 0) {
            onStatus("没有需要刮削的新视频")
            return
        }
        onStatus("待处理 $total 个视频，启动 $workers 线程…")
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                pool.submit {
                    while (!shouldCancel()) {
                        val work = queue.poll() ?: break
                        if (!rebuild && store.hasScrapeRecord(work.path)) continue
                        val item = runCatching {
                            buildItem(context, store, work)
                        }.getOrNull() ?: continue
                        synchronized(store) {
                            store.recordScrapedPath(work.path)
                        }
                        onFile(item)
                        val n = done.incrementAndGet()
                        if (n % 3 == 0 || n == total) {
                            onStatus("已刮削 $n / $total · ${work.name}")
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
        } finally {
            pool.shutdownNow()
        }
        onStatus("目录遍历结束")
    }

    private fun collectVideos(
        store: MediaStore,
        dir: DocumentFile,
        parent: DocumentFile?,
        prefix: String,
        rebuild: Boolean,
        shouldCancel: () -> Boolean,
        out: ConcurrentLinkedQueue<VideoWork>,
    ) {
        for (child in dir.listFiles()) {
            if (shouldCancel()) return
            if (child.isDirectory) {
                collectVideos(store, child, dir, "$prefix/${child.name}", rebuild, shouldCancel, out)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in VIDEO_EXT) continue
                val path = child.uri.toString()
                if (!rebuild && store.hasScrapeRecord(path)) continue
                val folder = dir.name ?: ""
                out.add(VideoWork(dir, parent, child, name, path, folder))
            }
        }
    }

    private fun buildItem(context: Context, store: MediaStore, work: VideoWork): MediaItem {
        val name = work.name
        val path = work.path
        val dir = work.dir
        val parent = work.parent
        val folder = work.folder
        val child = work.videoFile

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
                collection = CollectionNames.sanitize(setName)
                collectionKey = collection
            }
            nfoColl.isNotBlank() -> {
                collection = CollectionNames.sanitize(nfoColl)
                collectionKey = collection
            }
            else -> collection = cleanFolderName(dir.uri.toString())
        }

        val harvested = TagHarvest.harvest(name, folder, nfoObj)
        val tags = TagPresetLibrary.mergeWithHarvested(name, harvested)
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

        return MediaItem.fromJson(o)
    }

    private fun cleanFolderName(dirUri: String): String {
        val seg = dirUri.substringAfterLast('%').substringAfterLast('/')
        return seg.ifBlank { "未分组" }
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}