package com.mediavault.data

object CollectionNames {
    fun sanitize(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return s
        val nested = Regex("<name>\\s*([^<]+?)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.trim()
        if (!nested.isNullOrBlank()) return nested
        s = s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return s
    }
}