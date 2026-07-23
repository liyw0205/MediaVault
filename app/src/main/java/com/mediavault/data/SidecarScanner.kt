package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

object SidecarScanner {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg",
    )
    private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")

    data class SidecarBundle(
        val subtitles: List<String>,
        val coverUri: String?,
        val coverLocal: String?,
        val nfoXml: String?,
        val siblingNames: List<String>,
    )

    fun listVideoSiblingNames(dir: DocumentFile): List<String> =
        dir.listFiles().mapNotNull { it.name }

    fun scanAroundVideo(
        context: Context,
        dir: DocumentFile,
        parent: DocumentFile?,
        videoName: String,
        videoPath: String,
        coversDir: File,
        includeCover: Boolean = true,
        includeNfo: Boolean = true,
        includeSubtitles: Boolean = true,
    ): SidecarBundle {
        val children = dir.listFiles()
        val byName = indexByName(children)
        val xml = if (includeNfo) pickNfoXml(context, byName, parent, dir.name ?: "", videoName) else null
        val coverPick = if (includeCover) cacheFirstCover(context, byName, videoName, videoPath, coversDir) else null
        val subs = if (includeSubtitles) {
            pickSubtitles(byName, videoName, context)
        } else emptyList()
        val siblings = children.mapNotNull { it.name }.filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext !in VIDEO_EXT
        }
        return SidecarBundle(subs, coverPick?.first, coverPick?.second, xml, siblings)
    }

    private data class IndexedChild(val file: DocumentFile, val name: String, val ext: String)

    private fun indexByName(children: Array<DocumentFile>): Map<String, IndexedChild> {
        val map = linkedMapOf<String, IndexedChild>()
        for (c in children) {
            if (!c.isFile) continue
            val name = c.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            // 大小写不敏感索引，避免 Poster.PNG 等漏匹配
            map.putIfAbsent(name.lowercase(), IndexedChild(c, name, ext))
            map.putIfAbsent(name, IndexedChild(c, name, ext))
        }
        return map
    }

    private fun pickNfoXml(
        context: Context,
        byName: Map<String, IndexedChild>,
        parent: DocumentFile?,
        folder: String,
        videoName: String,
    ): String? {
        val base = videoName.substringBeforeLast('.')
        val candidates = mutableListOf<String>()
        candidates += "$base.nfo"
        candidates += "$base.NFO"
        candidates += "movie.nfo"
        candidates += "Movie.nfo"
        candidates += "tvshow.nfo"
        candidates += "TVShow.nfo"
        if (folder.isNotBlank()) {
            candidates += "$folder.nfo"
            candidates += "$folder.NFO"
        }
        for (name in candidates) {
            val ic = byName[name] ?: byName[name.lowercase()] ?: continue
            val xml = context.contentResolver.openInputStream(ic.file.uri)?.bufferedReader()?.readText()
            if (!xml.isNullOrBlank()) return xml
        }
        if (parent != null && folder.isNotBlank()) {
            for (name in listOf("$folder.nfo", "$folder.NFO")) {
                val f = parent.findFile(name) ?: continue
                if (!f.isFile) continue
                val xml = context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.readText()
                if (!xml.isNullOrBlank()) return xml
            }
        }
        return null
    }

    /**
     * 候选封面名优先级：同名/poster/cover 优先于 fanart；再按同 base 前缀图。
     * 返回按优先级排列的 DocumentFile URI 列表。
     */
    private fun orderedCoverCandidates(byName: Map<String, IndexedChild>, videoName: String): List<IndexedChild> {
        val base = videoName.substringBeforeLast('.')
        val orderedNames = mutableListOf<String>()
        // 1) 视频同名图
        for (ext in IMG_EXT) {
            orderedNames += "$base.$ext"
            orderedNames += "$videoName.$ext"
        }
        // 2) poster / cover / thumb（列表封面优先）
        for (ext in IMG_EXT) {
            orderedNames += "poster.$ext"
            orderedNames += "$base-poster.$ext"
            orderedNames += "$base.poster.$ext"
            orderedNames += "cover.$ext"
            orderedNames += "$base-cover.$ext"
            orderedNames += "$base.cover.$ext"
            orderedNames += "folder.$ext"
            orderedNames += "thumb.$ext"
            orderedNames += "$base-thumb.$ext"
            orderedNames += "$base-thumbnail.$ext"
            orderedNames += "$base.thumb.$ext"
        }
        // 3) fanart 作后备
        for (ext in IMG_EXT) {
            orderedNames += "fanart.$ext"
            orderedNames += "$base-fanart.$ext"
            orderedNames += "$base.fanart.$ext"
            orderedNames += "$base-fanart1.$ext"
            orderedNames += "$base-backdrop.$ext"
        }
        val seen = linkedSetOf<String>()
        val out = mutableListOf<IndexedChild>()
        fun addName(name: String) {
            val ic = byName[name] ?: byName[name.lowercase()] ?: return
            val key = ic.file.uri.toString()
            if (key in seen) return
            if (ic.ext !in IMG_EXT) return
            seen += key
            out += ic
        }
        for (n in orderedNames) addName(n)
        // 4) 同目录、文件名以视频 base 开头的其它图（如 -fanart1）
        val baseLower = base.lowercase()
        for ((_, ic) in byName) {
            if (ic.ext !in IMG_EXT) continue
            if (!ic.name.lowercase().startsWith(baseLower)) continue
            val key = ic.file.uri.toString()
            if (key in seen) continue
            seen += key
            out += ic
        }
        return out
    }

    private fun cacheFirstCover(
        context: Context,
        byName: Map<String, IndexedChild>,
        videoName: String,
        videoPath: String,
        coversDir: File,
    ): Pair<String, String>? {
        for (ic in orderedCoverCandidates(byName, videoName)) {
            val local = cacheCoverFromUri(context, ic.file.uri, videoPath, coversDir) ?: continue
            return ic.file.uri.toString() to local
        }
        return null
    }

    private fun pickSubtitles(byName: Map<String, IndexedChild>, videoName: String, context: Context): List<String> {
        val base = videoName.substringBeforeLast('.')
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for ((_, ic) in byName) {
            if (ic.ext !in SUB_EXT) continue
            if (ic.name.startsWith(base, ignoreCase = true) ||
                ic.name.equals("$base.${ic.ext}", ignoreCase = true)
            ) {
                val u = ic.file.uri.toString()
                if (seen.add(u)) out.add(u)
            }
        }
        return SubtitlePrefs.sortSubtitlePaths(context, out)
    }

    fun cacheCoverFromUri(context: Context, coverUri: Uri, videoPath: String, coversDir: File): String? {
        val ext = guessImageExt(coverUri)
        val safeExt = if (ext in IMG_EXT) if (ext == "jpeg") "jpg" else ext else "jpg"
        val id = sha1("$videoPath|cover")
        val dest = File(coversDir, "$id.$safeExt")
        // 有效缓存直接复用；极小/非图片缓存强制重拉
        if (CoverFileCache.keepIfValid(dest)) return dest.absolutePath
        // 同 key 其它扩展的坏/旧文件清掉，避免库里指到旧 .jpg 而新写了 .png
        coversDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("$id.") && f.name != dest.name) {
                if (!CoverFileCache.isValidCoverFile(f)) runCatching { f.delete() }
            }
        }
        val ok = CoverFileCache.atomicWrite(dest) { tmp ->
            context.contentResolver.openInputStream(coverUri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: error("open cover stream failed")
        }
        return if (ok) dest.absolutePath else null
    }

    private fun guessImageExt(coverUri: Uri): String {
        val seg = coverUri.lastPathSegment?.substringAfterLast('/') ?: return "jpg"
        // SAF document id 可能是 primary:path/to/file-poster.png
        val name = seg.substringAfterLast(':').substringAfterLast('/')
        return name.substringAfterLast('.', "jpg").lowercase()
    }

    fun seasonEpisodeFromName(name: String): Pair<String, String> {
        val base = name.substringBeforeLast('.')
        val season = Regex("(?i).*s(\\d{1,2})e\\d{1,3}.*").find(base)?.groupValues?.get(1).orEmpty()
        val episode = Regex("(?i).*s\\d{1,2}e(\\d{1,3}).*").find(base)?.groupValues?.get(1).orEmpty()
        return season to episode
    }

    fun yearFromName(name: String): String =
        Regex("(?:^|[^0-9])((19|20)\\d{2})(?:[^0-9]|$)").find(name.substringBeforeLast('.'))
            ?.groupValues?.get(1).orEmpty()

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
