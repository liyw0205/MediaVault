package com.mediavault.data

import org.json.JSONArray
import org.json.JSONObject

object NfoParser {
    fun parseXml(xml: String): JSONObject {
        val o = JSONObject()
        fun first(tag: String): String =
            Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)?.trim()?.replace(Regex("<!\\[CDATA\\[|\\]\\]>"), "")
                ?.trim().orEmpty()

        fun all(tag: String): List<String> {
            val out = mutableListOf<String>()
            val re = Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
            for (m in re.findAll(xml)) {
                val v = m.groupValues[1].trim().replace(Regex("<!\\[CDATA\\[|\\]\\]>"), "").trim()
                if (v.isNotBlank()) out.add(v)
            }
            return out
        }

        val title = first("title")
        if (title.isNotBlank()) o.put("title", title)
        val plot = first("plot").ifBlank { first("outline") }
        if (plot.isNotBlank()) o.put("plot", plot)
        for (tag in listOf(
            "year", "originaltitle", "title_cn", "title_jp", "title_rm", "sorttitle", "tagline",
            "premiered", "releasedate", "studio", "country", "director", "writer", "mpaa",
            "rating", "runtime", "status", "collection", "season", "episode",
        )) {
            val v = first(tag)
            if (v.isNotBlank()) o.put(tag, v)
        }
        val setName = Regex("<set[^>]*>\\s*<name>([^<]*)</name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank {
                val inner = first("set")
                inner.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            }
        if (setName.isNotBlank()) o.put("set_name", setName)

        val genres = JSONArray()
        for (g in all("genre")) genres.put(g)
        if (genres.length() > 0) o.put("genres_xml", genres)

        val tags = JSONArray()
        for (t in all("tag")) tags.put(t)
        if (tags.length() > 0) o.put("tags_xml", tags)

        return o
    }
}