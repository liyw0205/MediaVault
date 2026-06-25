package com.mediavault.remote

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.concurrent.TimeUnit

class WebDavClient(private val cfg: RemoteConfig) : RemoteClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String {
        val scheme = if (cfg.port == 443 || cfg.port == 8443) "https" else "http"
        val hostPort = if ((scheme == "https" && cfg.port == 443) || (scheme == "http" && cfg.port == 80)) {
            cfg.host
        } else {
            "${cfg.host}:${cfg.port}"
        }
        var path = cfg.basePath.trim()
        if (!path.startsWith("/")) path = "/$path"
        if (!path.endsWith("/")) path += "/"
        return "$scheme://$hostPort$path"
    }

    private fun authHeader(): String? {
        if (cfg.user.isBlank()) return null
        return Credentials.basic(cfg.user, cfg.password)
    }

    override fun testConnection(): String {
        val entries = list("")
        return "WebDAV OK，条目 ${entries.size}（根）"
    }

    override fun list(path: String): List<RemoteEntry> {
        var p = path.trim()
        if (p.startsWith("/")) p = p.drop(1)
        val url = baseUrl() + p
        val req = Request.Builder().url(url).method("PROPFIND", okhttp3.RequestBody.create(null, ByteArray(0)))
            .header("Depth", "1")
        authHeader()?.let { req.header("Authorization", it) }
        http.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("WebDAV ${resp.code}: ${resp.message}")
            val body = resp.body?.string() ?: return emptyList()
            return parsePropfind(body, p)
        }
    }

    private fun parsePropfind(xml: String, parentPath: String): List<RemoteEntry> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        val out = mutableListOf<RemoteEntry>()
        var event = parser.eventType
        var href = ""
        var isCollection = false
        var contentLength = 0L
        var displayName = ""
        val parentNorm = parentPath.trimEnd('/')
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "href" -> href = ""
                    "collection" -> isCollection = true
                    "getcontentlength" -> contentLength = 0L
                    "displayname" -> displayName = ""
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim() ?: ""
                    when (parser.name) {
                        "href" -> href = t
                        "getcontentlength" -> contentLength = t.toLongOrNull() ?: 0L
                        "displayname" -> displayName = t
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    if (href.isNotBlank()) {
                        val rel = hrefToRelative(href, parentNorm)
                        if (rel != null && rel != "." && rel != "..") {
                            val name = if (displayName.isNotBlank()) displayName else rel.substringAfterLast('/')
                            out.add(RemoteEntry(rel, name, isCollection, contentLength))
                        }
                    }
                    href = ""
                    isCollection = false
                    contentLength = 0L
                    displayName = ""
                }
            }
            event = parser.next()
        }
        return out.distinctBy { it.path }
    }

    private fun hrefToRelative(href: String, parentPath: String): String? {
        var h = href.trim()
        if (h.startsWith("http")) {
            val base = baseUrl()
            if (!h.startsWith(base)) return null
            h = h.removePrefix(base)
        }
        h = h.trimStart('/')
        if (parentPath.isNotEmpty() && h == parentPath.trimStart('/')) return null
        return h
    }
}