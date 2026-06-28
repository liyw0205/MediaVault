package com.mediavault.data

import org.json.JSONArray

/** 从 NFO 和文件名里收集标签（常用规则；不含父目录名、不含文件名首段［厂商］） */
object TagHarvest {
    fun harvest(fileName: String, parentFolder: String, nfo: org.json.JSONObject): List<String> {
        @Suppress("UNUSED_PARAMETER")
        val _folderUnused = parentFolder // 保留参数以兼容 LocalScanner / RemoteLibraryScanner 调用
        val seen = linkedSetOf<String>()
        fun add(t: String) {
            val x = t.trim()
            if (x.isNotBlank()) seen.add(x)
        }

        val tagsXml = nfo.optJSONArray("tags_xml")
        if (tagsXml != null) {
            for (i in 0 until tagsXml.length()) add(tagsXml.optString(i))
        }
        val genresXml = nfo.optJSONArray("genres_xml")
        if (genresXml != null) {
            for (i in 0 until genresXml.length()) add(genresXml.optString(i))
        }

        val base = fileName.substringBeforeLast('.')
        // 勿将文件名首段 ［厂商］ / [Studio] 写入标签（仅作发行商标识，非内容标签）

        val name = base
        val lower = name.lowercase()
        val token = " $lower ".replace(Regex("[^a-z0-9]+"), " ")

        if (name.contains("中文") || name.contains("中字") || name.contains("国语") ||
            token.contains(" chinese ") || token.contains(" chs ") || token.contains(" cn ")
        ) add("中文")
        if (name.contains("英文") || name.contains("英字") || token.contains(" english ")) add("英文")
        if (name.contains("日文") || name.contains("日语") || token.contains(" japanese ")) add("日文")
        if (token.contains(" ova ")) add("OVA")
        if (name.contains("NTR") || name.contains("ntr")) add("NTR")
        if (name.contains("巨乳")) add("巨乳")
        if (name.contains("ハーレム") || lower.contains("harem")) add("后宫")

        // 勿把视频所在父文件夹名写入 tags（避免同目录整批共用一个 tag: 合集键）

        return seen.toList()
    }

    fun toJsonArray(tags: List<String>): JSONArray {
        val arr = JSONArray()
        if (tags.isEmpty()) arr.put("无") else tags.forEach { arr.put(it) }
        return arr
    }
}