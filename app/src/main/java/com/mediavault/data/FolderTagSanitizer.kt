package com.mediavault.data

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 父目录名写入标签前的规范化（避免 URI 残片如 2F2025） */
object FolderTagSanitizer {
    /**
     * 是否应把父目录名当作标签。
     * - 纯年份目录（2025）不写入标签，避免整夹视频共用一个无意义合集键。
     * - `2F2025` 等为 `%2F` 被错误截断后的残片，丢弃。
     */
    fun folderAsTag(parentFolder: String): String? {
        var f = parentFolder.trim()
        if (f.isBlank() || f == "." || f == "root") return null
        f = runCatching {
            URLDecoder.decode(f, StandardCharsets.UTF_8.name())
        }.getOrDefault(f).trim()
        if (Regex("(?i)^2F(\\d{4})$").matches(f)) return null
        if (Regex("^\\d{4}$").matches(f)) return null
        if (f.contains('/') || f.contains('%')) {
            val last = f.substringAfterLast('/').trim()
            if (last.isBlank()) return null
            f = last
            if (Regex("^\\d{4}$").matches(f)) return null
            if (Regex("(?i)^2F(\\d{4})$").matches(f)) return null
        }
        return f
    }

    /** 从 content/document URI 取最后一级目录名（用于 collection 等） */
    fun lastSegmentFromTreeUri(dirUri: String): String {
        val decoded = runCatching {
            URLDecoder.decode(dirUri, StandardCharsets.UTF_8.name())
        }.getOrDefault(dirUri)
        val tail = decoded.substringAfterLast(':').substringAfterLast('/').trim()
        if (tail.isNotBlank()) return tail
        val legacy = dirUri.substringAfterLast('/').substringBefore('?').trim()
        return legacy.ifBlank { "未分组" }
    }
}