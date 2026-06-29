package com.mediavault.data

/** 无 NFO 时从文件名匹配的预设标签（可扩展 assets） */
object TagPresetLibrary {
    private val PRESETS = listOf(
        "中文", "英文", "日文", "无字幕", "OVA", "剧场版", "TV", "4K", "1080P", "720P",
        "HEVC", "H.264", "HDR", "杜比", "内嵌字幕", "外挂字幕",
    )

    fun matchFromFileName(fileName: String): List<String> {
        val base = fileName.substringBeforeLast('.')
        val lower = base.lowercase()
        val token = " $lower ".replace(Regex("[^a-z0-9]+"), " ")
        val out = linkedSetOf<String>()
        for (p in PRESETS) {
            val pl = p.lowercase()
            if (base.contains(p, ignoreCase = true)) out.add(p)
            else if (pl.length >= 2 && token.contains(" $pl ")) out.add(p)
        }
        if (Regex("(?i)2160p|4k").containsMatchIn(base)) out.add("4K")
        if (Regex("(?i)1080p").containsMatchIn(base)) out.add("1080P")
        if (Regex("(?i)720p").containsMatchIn(base)) out.add("720P")
        if (Regex("(?i)x265|hevc|h265").containsMatchIn(base)) out.add("HEVC")
        if (Regex("(?i)x264|h264|avc").containsMatchIn(base)) out.add("H.264")
        return out.toList()
    }

    fun mergeWithHarvested(fileName: String, harvested: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        harvested.forEach { if (it.isNotBlank() && it != "无") seen.add(it) }
        if (seen.isEmpty()) {
            matchFromFileName(fileName).forEach { seen.add(it) }
        } else {
            matchFromFileName(fileName).forEach { seen.add(it) }
        }
        return if (seen.isEmpty()) listOf("无") else seen.toList()
    }
}