package com.mediavault.data

import com.mediavault.remote.RemoteClients
import com.mediavault.remote.RemoteConfig
import com.mediavault.remote.RemoteEntry
import com.mediavault.remote.RemotePath
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object RemoteLibraryScanner {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg",
    )

    fun scanRemotesParallel(
        store: MediaStore,
        rebuild: Boolean,
        remoteIdsFilter: List<String>?,
        threadCount: Int,
        shouldCancel: () -> Boolean,
        onFile: (MediaItem) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        var configs = store.readRemotesList()
        if (!remoteIdsFilter.isNullOrEmpty()) {
            configs = configs.filter { it.id in remoteIdsFilter }
        }
        if (configs.isEmpty()) throw IllegalStateException("请先在设置中添加 WebDAV/FTP/SMB 远程")

        val workers = threadCount.coerceIn(ScrapeConfig.MIN_THREADS, ScrapeConfig.MAX_THREADS)
        val queue = ConcurrentLinkedQueue<Work>()
        onStatus("枚举远程视频…")
        for (cfg in configs) {
            if (shouldCancel()) return
            onStatus("扫描 ${cfg.name} (${cfg.type})…")
            val client = RemoteClients.create(cfg)
            collectVideos(cfg, client, "", rebuild, store, shouldCancel, queue)
        }
        val total = queue.size
        if (total == 0) {
            onStatus("远程目录下没有新视频")
            return
        }
        onStatus("待处理 $total 个远程视频，$workers 线程…")
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                pool.submit {
                    while (!shouldCancel()) {
                        val w = queue.poll() ?: break
                        val libPath = RemotePath.encode(w.config.id, w.relPath)
                        if (!rebuild && store.hasScrapeRecord(libPath)) continue
                        val item = buildItem(w)
                        synchronized(store) { store.recordScrapedPath(libPath) }
                        onFile(item)
                        val n = done.incrementAndGet()
                        if (n % 3 == 0 || n == total) {
                            onStatus("已入库 $n / $total · ${w.name}")
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
        } finally {
            pool.shutdownNow()
        }
        onStatus("远程扫描结束")
    }

    private data class Work(
        val config: RemoteConfig,
        val relPath: String,
        val name: String,
        val size: Long,
        val folder: String,
    )

    private fun collectVideos(
        cfg: RemoteConfig,
        client: com.mediavault.remote.RemoteClient,
        relDir: String,
        rebuild: Boolean,
        store: MediaStore,
        shouldCancel: () -> Boolean,
        out: ConcurrentLinkedQueue<Work>,
    ) {
        val entries = runCatching { client.list(relDir) }.getOrElse { emptyList() }
        for (e in entries) {
            if (shouldCancel()) return
            val childRel = joinPath(relDir, e.path, e.name, e.directory)
            if (e.directory) {
                collectVideos(cfg, client, childRel, rebuild, store, shouldCancel, out)
            } else {
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext !in VIDEO_EXT) continue
                val libPath = RemotePath.encode(cfg.id, childRel)
                if (!rebuild && store.hasScrapeRecord(libPath)) continue
                val folder = childRel.substringBeforeLast('/', "").substringAfterLast('/')
                out.add(Work(cfg, childRel, e.name, e.size, folder))
            }
        }
    }

    private fun joinPath(parent: String, entryPath: String, name: String, dir: Boolean): String {
        val p = when {
            entryPath.isNotBlank() -> entryPath.replace('\\', '/').trimStart('/')
            parent.isBlank() -> name
            else -> "${parent.trimEnd('/')}/$name"
        }
        return p.replace('\\', '/')
    }

    private fun buildItem(w: Work): MediaItem {
        val clean = w.name.substringBeforeLast('.')
        val path = RemotePath.encode(w.config.id, w.relPath)
        val (season, episode) = SidecarScanner.seasonEpisodeFromName(w.name)
        var year = SidecarScanner.yearFromName(w.name)
        val folder = w.folder.ifBlank { w.config.name }
        val harvested = TagHarvest.harvest(w.name, folder, JSONObject())
        val tags = TagPresetLibrary.mergeWithHarvested(w.name, harvested)
        val collection = CollectionNames.sanitize(folder.ifBlank { clean })
        val o = JSONObject()
        o.put("path", path)
        o.put("title", clean)
        o.put("clean_title", clean)
        o.put("size", w.size)
        o.put("modified", fmtNow())
        o.put("folder", folder)
        o.put("collection", collection)
        o.put("collection_key_name", collection)
        o.put("year", year)
        o.put("season", season)
        o.put("episode", episode)
        o.put("remote_id", w.config.id)
        o.put("remote_type", w.config.type)
        o.put("remote_rel", w.relPath)
        o.put("tags", TagHarvest.toJsonArray(tags))
        o.put("genres", JSONArray())
        return MediaItem.fromJson(o)
    }

    private fun fmtNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}