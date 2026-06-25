package com.mediavault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
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

    fun findNfoFile(context: Context, dir: DocumentFile, parent: DocumentFile?, videoName: String): Pair<DocumentFile?, String?> {
        val base = videoName.substringBeforeLast('.')
        val folder = dir.name ?: ""
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
            val f = dir.findFile(name) ?: continue
            if (!f.isFile) continue
            val xml = context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.readText()
            if (!xml.isNullOrBlank()) return f to xml
        }
        if (parent != null && folder.isNotBlank()) {
            for (name in listOf("$folder.nfo", "$folder.NFO")) {
                val f = parent.findFile(name) ?: continue
                if (!f.isFile) continue
                val xml = context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.readText()
                if (!xml.isNullOrBlank()) return f to xml
            }
        }
        return null to null
    }

    fun findCoverUri(dir: DocumentFile, videoName: String): Uri? {
        val base = videoName.substringBeforeLast('.')
        val names = mutableListOf<String>()
        for (ext in IMG_EXT) {
            names += "$base.$ext"
            names += "$base-poster.$ext"
            names += "$base-cover.$ext"
            names += "$base-thumb.$ext"
            names += "$base-thumbnail.$ext"
            names += "$base-fanart.$ext"
            names += "$videoName.$ext"
        }
        for (name in names) {
            val f = dir.findFile(name) ?: continue
            if (f.isFile) return f.uri
        }
        return null
    }

    fun findSubtitles(dir: DocumentFile, videoName: String): List<String> {
        val base = videoName.substringBeforeLast('.')
        val out = mutableListOf<String>()
        for (child in dir.listFiles()) {
            if (!child.isFile) continue
            val name = child.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUB_EXT) continue
            if (name.startsWith(base, ignoreCase = true) || name.equals("$base.$ext", ignoreCase = true)) {
                out.add(child.uri.toString())
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

    fun scanAroundVideo(
        context: Context,
        dir: DocumentFile,
        parent: DocumentFile?,
        videoName: String,
        videoPath: String,
        coversDir: File,
    ): SidecarBundle {
        val (_, xml) = findNfoFile(context, dir, parent, videoName)
        val coverUri = findCoverUri(dir, videoName)
        val coverLocal = coverUri?.let { cacheCoverFromUri(context, it, videoPath, coversDir) }
        val subs = findSubtitles(dir, videoName)
        val siblings = listVideoSiblingNames(dir).filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext !in VIDEO_EXT
        }
        return SidecarBundle(subs, coverUri?.toString(), coverLocal, xml, siblings)
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