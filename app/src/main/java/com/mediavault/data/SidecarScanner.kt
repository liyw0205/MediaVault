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
    ): SidecarBundle {
        val children = dir.listFiles()
        val byName = indexByName(children)
        val xml = pickNfoXml(context, byName, parent, dir.name ?: "", videoName)
        val coverUri = pickCoverUri(byName, videoName)
        val coverLocal = coverUri?.let { cacheCoverFromUri(context, it, videoPath, coversDir) }
        val subs = pickSubtitles(byName, videoName)
        val siblings = children.mapNotNull { it.name }.filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext !in VIDEO_EXT
        }
        return SidecarBundle(subs, coverUri?.toString(), coverLocal, xml, siblings)
    }

    private data class IndexedChild(val file: DocumentFile, val name: String, val ext: String)

    private fun indexByName(children: Array<DocumentFile>): Map<String, IndexedChild> {
        val map = linkedMapOf<String, IndexedChild>()
        for (c in children) {
            if (!c.isFile) continue
            val name = c.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
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
            val ic = byName[name] ?: continue
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

    private fun pickCoverUri(byName: Map<String, IndexedChild>, videoName: String): Uri? {
        val base = videoName.substringBeforeLast('.')
        val ordered = mutableListOf<String>()
        for (ext in IMG_EXT) {
            ordered += "$videoName.$ext"
            ordered += "$base.$ext"
        }
        for (ext in IMG_EXT) {
            ordered += "fanart.$ext"
            ordered += "$base-fanart.$ext"
            ordered += "$base.fanart.$ext"
        }
        for (ext in IMG_EXT) {
            ordered += "poster.$ext"
            ordered += "$base-poster.$ext"
            ordered += "$base.poster.$ext"
        }
        for (ext in IMG_EXT) {
            ordered += "folder.$ext"
            ordered += "cover.$ext"
            ordered += "$base-cover.$ext"
            ordered += "$base-thumb.$ext"
            ordered += "$base-thumbnail.$ext"
            ordered += "thumb.$ext"
        }
        for ((name, ic) in byName) {
            if (ic.ext !in IMG_EXT) continue
            if (name !in ordered) ordered += name
        }
        for (name in ordered) {
            val ic = byName[name] ?: continue
            return ic.file.uri
        }
        return null
    }

    private fun pickSubtitles(byName: Map<String, IndexedChild>, videoName: String): List<String> {
        val base = videoName.substringBeforeLast('.')
        val out = mutableListOf<String>()
        for ((name, ic) in byName) {
            if (ic.ext !in SUB_EXT) continue
            if (name.startsWith(base, ignoreCase = true) || name.equals("$base.${ic.ext}", ignoreCase = true)) {
                out.add(ic.file.uri.toString())
            }
        }
        return out.sorted()
    }

    fun cacheCoverFromUri(context: Context, coverUri: Uri, videoPath: String, coversDir: File): String? {
        val ext = coverUri.lastPathSegment?.substringAfterLast('.', "jpg")?.lowercase() ?: "jpg"
        val safeExt = if (ext in IMG_EXT) if (ext == "jpeg") "jpg" else ext else "jpg"
        val id = sha1("$videoPath|cover")
        val dest = File(coversDir, "$id.$safeExt")
        if (dest.isFile && dest.length() > 0) return dest.absolutePath
        return try {
            context.contentResolver.openInputStream(coverUri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (dest.isFile) dest.absolutePath else null
        } catch (_: Exception) {
            null
        }
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