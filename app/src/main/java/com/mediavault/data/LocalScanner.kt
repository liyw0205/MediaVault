package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mediavault.scrape.ScrapeProgressFormat
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
        scrapeSession: ScrapeSession,
        settings: ScrapeSettings,
        rebuild: Boolean,
        threadCount: Int,
        rootUrisFilter: List<String>? = null,
        frameGate: RemoteFrameGate,
        shouldCancel: () -> Boolean,
        onFile: (MediaItem) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val cfg = settings.normalized()
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
            collectVideos(scrapeSession, root, null, root.name ?: "root", rebuild, shouldCancel, queue)
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
                        if (!rebuild && scrapeSession.has(work.path)) continue
                        val item = runCatching {
                            buildItem(context, store, work, cfg, frameGate)
                        }.getOrNull() ?: continue
                        scrapeSession.record(store, work.path)
                        onFile(item)
                        val n = done.incrementAndGet()
                        if (n % 3 == 0 || n == total) {
                            onStatus(ScrapeProgressFormat.ellipsizeFileName(work.name))
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
        scrapeSession: ScrapeSession,
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
                collectVideos(scrapeSession, child, dir, "$prefix/${child.name}", rebuild, shouldCancel, out)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in VIDEO_EXT) continue
                val path = child.uri.toString()
                if (!rebuild && scrapeSession.has(path)) continue
                val folder = dir.name ?: ""
                out.add(VideoWork(dir, parent, child, name, path, folder))
            }
        }
    }

    private fun buildItem(
        context: Context,
        store: MediaStore,
        work: VideoWork,
        cfg: ScrapeSettings,
        frameGate: RemoteFrameGate,
    ): MediaItem {
        val name = work.name
        val path = work.path
        val dir = work.dir
        val parent = work.parent
        val folder = work.folder
        val child = work.videoFile

        val sidecar = SidecarScanner.scanAroundVideo(
            context, dir, parent, name, path, store.coversDir,
            includeCover = cfg.coverFromFiles,
            includeNfo = cfg.metadataFromNfo,
            includeSubtitles = cfg.scanSidecarSubtitles,
        )
        val nfoObj = if (cfg.metadataFromNfo && !sidecar.nfoXml.isNullOrBlank()) {
            NfoParser.parseXml(sidecar.nfoXml)
        } else {
            JSONObject()
        }

        val clean = name.substringBeforeLast('.')
        var title = clean
        if (cfg.metadataFromNfo) {
            val titleCn = nfoObj.optString("title_cn", "").trim()
            val nfoTitle = nfoObj.optString("title", "").trim()
            title = when {
                titleCn.isNotBlank() -> titleCn
                nfoTitle.isNotBlank() -> nfoTitle
                else -> clean
            }
        } else if (cfg.metadataFromFilename) {
            title = clean
        }

        var year = if (cfg.metadataFromFilename) SidecarScanner.yearFromName(name) else ""
        if (cfg.metadataFromNfo) {
            val nfoYear = nfoObj.optString("year", "").trim()
            if (nfoYear.isNotBlank()) year = nfoYear
        }

        val (season, episode) = if (cfg.metadataFromFilename) {
            SidecarScanner.seasonEpisodeFromName(name)
        } else {
            "" to ""
        }
        var collection = folder.trim()
        val setName = if (cfg.metadataFromNfo) nfoObj.optString("set_name", "").trim() else ""
        val nfoColl = if (cfg.metadataFromNfo) nfoObj.optString("collection", "").trim() else ""
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

        val harvested = if (cfg.metadataFromFilename || cfg.metadataFromNfo) {
            TagHarvest.harvest(name, folder, nfoObj)
        } else {
            emptyList()
        }
        val tags = if (harvested.isNotEmpty()) {
            TagPresetLibrary.mergeWithHarvested(name, harvested)
        } else {
            emptyList()
        }
        val genres = mutableListOf<String>()
        if (cfg.metadataFromNfo) {
            val ga = nfoObj.optJSONArray("genres_xml")
            if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optString(i))
        }

        val plot = if (cfg.metadataFromNfo) nfoObj.optString("plot", "").trim() else ""

        var coverLocal = sidecar.coverLocal
        var coverSource = if (coverLocal != null) "sidecar" else ""
        if (coverLocal == null && cfg.coverFromVideoFrame) {
            val framed = LocalFrameCoverExtractor.cacheFrameCover(
                context,
                child.uri,
                path,
                store.coversDir,
                frameGate,
            )
            if (framed != null) {
                coverLocal = framed
                coverSource = "frame"
            }
        }

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
        o.put("season", season.ifBlank { if (cfg.metadataFromNfo) nfoObj.optString("season", "") else "" })
        o.put("episode", episode.ifBlank { if (cfg.metadataFromNfo) nfoObj.optString("episode", "") else "" })
        o.put("plot", plot)
        o.put("tags", TagHarvest.toJsonArray(tags))
        o.put("genres", JSONArray().also { arr -> genres.forEach { arr.put(it) } })
        if (cfg.metadataFromNfo && nfoObj.length() > 0) o.put("nfo", nfoObj)
        if (sidecar.coverUri != null) o.put("cover_uri", sidecar.coverUri)
        if (coverLocal != null) {
            o.put("cover_local", coverLocal)
            o.put("cover_source", coverSource)
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
            if (!cfg.metadataFromNfo) continue
            val v = nfoObj.optString(key, "").trim()
            if (v.isNotBlank()) o.put(key, v)
        }

        val coverForOnline = o.optString("cover_local", "").trim().takeIf { it.isNotBlank() }
        val enriched = OnlineMetadataEnricher.enrichIfEnabled(
            context, store, cfg, name, path, o, coverForOnline,
        )
        return MediaItem.fromJson(enriched)
    }

    private fun cleanFolderName(dirUri: String): String =
        FolderTagSanitizer.lastSegmentFromTreeUri(dirUri)

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}