package com.mediavault.remote

import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.media3.common.C
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class WebDavClient(private val cfg: RemoteConfig) : RemoteClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun requestUrl(relativePath: String, directory: Boolean = false): String {
        val scheme = if (cfg.port == 443 || cfg.port == 8443) "https" else "http"
        val hostPort = if ((scheme == "https" && cfg.port == 443) || (scheme == "http" && cfg.port == 80)) {
            cfg.host
        } else {
            "${cfg.host}:${cfg.port}"
        }
        val base = normalizePath(cfg.basePath)
        val rel = normalizeRelativePath(relativePath, base)
        val joined = when {
            base.isBlank() -> rel
            rel.isBlank() -> base
            else -> "$base/$rel"
        }
        val encoded = joined.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { Uri.encode(it) }
        val suffix = if (directory && encoded.isNotBlank()) "/" else ""
        return if (encoded.isBlank()) {
            "$scheme://$hostPort/"
        } else {
            "$scheme://$hostPort/$encoded$suffix"
        }
    }

    private fun authHeader(): String? {
        if (cfg.user.isBlank()) return null
        return Credentials.basic(cfg.user, cfg.password)
    }

    override fun fileSize(relativePath: String): Long {
        val url = requestUrl(relativePath)
        val req = Request.Builder().url(url).head()
        authHeader()?.let { req.header("Authorization", it) }
        http.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) return C.LENGTH_UNSET.toLong()
            val cl = resp.header("Content-Length")?.toLongOrNull()
            if (cl != null && cl >= 0) return cl
            return C.LENGTH_UNSET.toLong()
        }
    }

    override fun openRead(
        relativePath: String,
        offset: Long,
        length: Long,
    ): java.io.InputStream {
        val url = requestUrl(relativePath)
        val req = Request.Builder().url(url).get()
        authHeader()?.let { req.header("Authorization", it) }
        val wantsRange = offset > 0 || length != C.LENGTH_UNSET.toLong()
        if (wantsRange) {
            val end = when {
                length != C.LENGTH_UNSET.toLong() && length > 0 -> offset + length - 1
                else -> ""
            }
            req.header("Range", "bytes=$offset-$end")
        }
        val resp = http.newCall(req.build()).execute()
        if (wantsRange && offset > 0 && resp.code != 206) {
            resp.close()
            error("WebDAV Range unsupported: ${resp.code}")
        }
        if (!resp.isSuccessful && resp.code != 206) {
            resp.close()
            error("WebDAV GET ${resp.code}")
        }
        val body = resp.body ?: run {
            resp.close()
            error("WebDAV empty body")
        }
        val raw = body.byteStream()
        val limited = if (length != C.LENGTH_UNSET.toLong() && length > 0) {
            RemoteLimitedInputStream(raw, length)
        } else {
            raw
        }
        return object : java.io.FilterInputStream(limited) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    resp.close()
                }
            }
        }
    }

    override fun testConnection(): String {
        val entries = list("")
        val dirs = entries.count { it.directory }
        return "WebDAV OK，条目 ${entries.size}（目录 $dirs）"
    }

    override fun list(path: String): List<RemoteEntry> {
        val parentRel = normalizeRelativePath(path)
        val url = requestUrl(path, directory = true)
        val body = ByteArray(0).toRequestBody("application/xml".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
        authHeader()?.let { req.header("Authorization", it) }
        http.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("WebDAV ${resp.code}: ${resp.message}")
            val xml = resp.body?.string() ?: return emptyList()
            return parsePropfind(xml, parentRel)
        }
    }

    private fun parsePropfind(xml: String, parentPath: String): List<RemoteEntry> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        val out = mutableListOf<RemoteEntry>()
        var event = parser.eventType
        var href = ""
        var isCollection = false
        var contentLength = 0L
        var displayName = ""
        var currentTextTag: String? = null
        val parentNorm = parentPath.trim('/')

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localTag(parser.name)
                    when (tag) {
                        "href" -> href = ""
                        "collection" -> isCollection = true
                        "getcontentlength" -> contentLength = 0L
                        "displayname" -> displayName = ""
                    }
                    currentTextTag = tag
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val t = parser.text?.trim() ?: ""
                    if (t.isNotEmpty()) {
                        when (currentTextTag) {
                            "href" -> href += t
                            "getcontentlength" -> contentLength = t.toLongOrNull() ?: contentLength
                            "displayname" -> displayName = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = localTag(parser.name)
                    if (tag == "response") {
                        if (href.isNotBlank()) {
                            val rel = hrefToRelative(href, parentNorm)
                            if (rel != null) {
                                val name = displayName.ifBlank { rel.substringAfterLast('/') }
                                if (name.isNotBlank()) {
                                    out.add(RemoteEntry(rel, name, isCollection, contentLength))
                                }
                            }
                        }
                        href = ""
                        isCollection = false
                        contentLength = 0L
                        displayName = ""
                    }
                    currentTextTag = null
                }
            }
            event = parser.next()
        }
        return out.distinctBy { it.path }
    }

    private fun localTag(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.substringAfter(':').lowercase()
    }

    private fun hrefToRelative(href: String, parentPath: String): String? {
        var h = runCatching { URLDecoder.decode(href.trim(), Charsets.UTF_8.name()) }.getOrDefault(href.trim())
        if (h.startsWith("http://", ignoreCase = true) || h.startsWith("https://", ignoreCase = true)) {
            h = runCatching { URI(h).path.orEmpty() }.getOrDefault(h)
        }
        h = h.trim()
        if (h.isEmpty()) return null

        val baseSeg = normalizePath(cfg.basePath)
        var pathOnly = h.trimStart('/')
        if (baseSeg.isNotEmpty()) {
            if (pathOnly == baseSeg || pathOnly == "$baseSeg/") return null
            if (pathOnly.startsWith("$baseSeg/")) {
                pathOnly = pathOnly.removePrefix("$baseSeg/")
            }
        }
        pathOnly = pathOnly.trim('/')
        if (pathOnly.isEmpty()) return null

        val parentNorm = parentPath.trim('/')
        val rel = when {
            parentNorm.isEmpty() -> pathOnly
            pathOnly == parentNorm -> null
            pathOnly.startsWith("$parentNorm/") -> pathOnly
            else -> {
                val tail = pathOnly.substringAfterLast('/')
                if (tail.isBlank()) null else if (parentNorm.isEmpty()) tail else "$parentNorm/$tail"
            }
        } ?: return null

        val childName = rel.substringAfterLast('/')
        if (childName.isBlank() || childName == "." || childName == "..") return null
        if (parentNorm.isNotEmpty() && rel == parentNorm) return null
        return rel
    }

    private fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').trim('/')

    private fun normalizeRelativePath(path: String, base: String = normalizePath(cfg.basePath)): String {
        val rel = normalizePath(path)
        if (base.isBlank()) return rel
        return when {
            rel == base -> ""
            rel.startsWith("$base/") -> rel.removePrefix("$base/").trim('/')
            else -> rel
        }
    }
}
