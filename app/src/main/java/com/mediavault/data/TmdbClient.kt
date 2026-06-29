package com.mediavault.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TmdbClient {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Match(
        val mediaType: String,
        val tmdbId: Int,
        val title: String,
        val originalTitle: String,
        val year: String,
        val plot: String,
        val genres: List<String>,
        val posterUrl: String?,
        val collectionName: String,
        val season: String,
        val episode: String,
        val episodeTitle: String = "",
        val episodePlot: String = "",
        val episodeStillUrl: String? = null,
        val episodeAirDate: String = "",
        val confidence: String = "",
    )

    private data class CacheKey(
        val apiKey: String,
        val fileName: String,
        val season: String,
        val episode: String,
        val year: String,
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<CacheKey, Pair<Long, Match?>>()
    private const val CACHE_TTL_MS = 30L * 60_000L

    fun lookup(apiKey: String, fileName: String, seasonHint: String, episodeHint: String, yearHint: String): Match? {
        val key = apiKey.trim()
        if (key.isBlank()) return null
        val ck = CacheKey(key, fileName, seasonHint, episodeHint, yearHint)
        val now = System.currentTimeMillis()
        cache[ck]?.let { (ts, m) ->
            if (now - ts < CACHE_TTL_MS) return m
            cache.remove(ck)
        }
        val clean = fileName.substringBeforeLast('.')
        val query = queryFromFilename(clean)
        if (query.length < 2) {
            cache[ck] = now to null
            return null
        }
        val isTv = seasonHint.isNotBlank() || episodeHint.isNotBlank() ||
            Regex("(?i)s\\d{1,2}e\\d{1,3}").containsMatchIn(clean)
        val match = if (isTv) {
            searchTv(key, query, seasonHint, episodeHint, yearHint)
                ?: searchMovie(key, query, yearHint)
        } else {
            searchMovie(key, query, yearHint)
                ?: searchTv(key, query, seasonHint, episodeHint, yearHint)
        }
        cache[ck] = now to match
        return match
    }

    /** 清空内存中的 TMDB 响应缓存（重建扫描前可主动调一次） */
    fun clearCache() = cache.clear()

    /** 手动重新匹配：按标题搜索，返回最多 [limit] 条候选（电影 + 剧集合并排序）。 */
    fun searchCandidates(
        apiKey: String,
        title: String,
        yearHint: String,
        seasonHint: String,
        episodeHint: String,
        limit: Int = 3,
    ): List<Match> {
        val key = apiKey.trim()
        val query = title.trim()
        if (key.isBlank() || query.length < 2) return emptyList()
        val isTv = seasonHint.isNotBlank() || episodeHint.isNotBlank()
        val out = mutableListOf<Match>()
        if (isTv) {
            searchTvCandidates(key, query, seasonHint, episodeHint, yearHint, limit)?.let { out.addAll(it) }
            if (out.size < limit) {
                searchMovieCandidates(key, query, yearHint, limit - out.size)?.let { out.addAll(it) }
            }
        } else {
            searchMovieCandidates(key, query, yearHint, limit)?.let { out.addAll(it) }
            if (out.size < limit) {
                searchTvCandidates(key, query, seasonHint, episodeHint, yearHint, limit - out.size)?.let { out.addAll(it) }
            }
        }
        return out.take(limit)
    }

    private fun searchMovieCandidates(apiKey: String, query: String, yearHint: String, limit: Int): List<Match>? {
        val url = buildString {
            append(BASE).append("/search/movie?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val matches = mutableListOf<Match>()
        for (i in 0 until minOf(arr.length(), limit)) {
            val hit = arr.optJSONObject(i) ?: continue
            val id = hit.optInt("id", 0)
            if (id <= 0) continue
            val detail = getJson("$BASE/movie/$id?api_key=$apiKey&language=zh-CN") ?: continue
            matches.add(movieToMatch(detail, hit).copy(confidence = "manual_pick"))
        }
        return matches.ifEmpty { null }
    }

    private fun searchTvCandidates(
        apiKey: String,
        query: String,
        season: String,
        episode: String,
        yearHint: String,
        limit: Int,
    ): List<Match>? {
        val url = buildString {
            append(BASE).append("/search/tv?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&first_air_date_year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val matches = mutableListOf<Match>()
        for (i in 0 until minOf(arr.length(), limit)) {
            val hit = arr.optJSONObject(i) ?: continue
            val id = hit.optInt("id", 0)
            if (id <= 0) continue
            val detail = getJson("$BASE/tv/$id?api_key=$apiKey&language=zh-CN") ?: continue
            var base = tvToMatch(detail, hit, season, episode).copy(confidence = "manual_pick")
            if (season.isNotBlank() && episode.isNotBlank()) {
                val sNum = season.toIntOrNull()
                val eNum = episode.toIntOrNull()
                if (sNum != null && eNum != null) {
                    val ep = getJson("$BASE/tv/$id/season/$sNum/episode/$eNum?api_key=$apiKey&language=zh-CN")
                    if (ep != null) {
                        base = base.copy(
                            episodeTitle = ep.optString("name", "").trim(),
                            episodePlot = ep.optString("overview", "").trim(),
                            episodeStillUrl = posterUrl(ep.optString("still_path", "")),
                            episodeAirDate = ep.optString("air_date", "").trim(),
                        )
                    }
                }
            }
            matches.add(base)
        }
        return matches.ifEmpty { null }
    }

    private fun queryFromFilename(clean: String): String {
        var q = clean
        q = Regex("(?i)\\s*s\\d{1,2}e\\d{1,3}.*").replace(q, "")
        q = Regex("\\(\\d{4}\\)").replace(q, "")
        q = Regex("(?:^|[^0-9])(19|20)\\d{2}(?:[^0-9]|$)").replace(q, " ")
        q = stripTechTags(q)
        q = q.replace(Regex("[\\[\\]【】._\\-]+"), " ")
        return q.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripTechTags(s: String): String {
        var q = s
        // 分辨率 / 编码 / 码率
        q = Regex("(?i)\\b(2160p|1080p|1080i|720p|480p|4k|uhd|hdr10\\+?|hdr|sdr|dv|dolby[\\s.-]?vision)\\b").replace(q, " ")
        q = Regex("(?i)\\b(x264|x265|h\\.?264|h\\.?265|hevc|avc|av1|vp9)\\b").replace(q, " ")
        q = Regex("(?i)\\b(10bit|8bit|10[\\s.-]?bit)\\b").replace(q, " ")
        // 来源
        q = Regex("(?i)\\b(web[\\s.-]?dl|webrip|web|bluray|blu[\\s.-]?ray|bdrip|bd|hdtv|dvdrip|remux|hdrip)\\b").replace(q, " ")
        // 音轨
        q = Regex("(?i)\\b(dts(-?hd)?|truehd|atmos|aac|ac3|eac3|ddp?\\d?(\\.\\d)?|flac|opus|mp3)\\b").replace(q, " ")
        q = Regex("(?i)\\b\\d\\.\\d\\b").replace(q, " ")
        // 常见中文标记
        q = q.replace(Regex("(国语|粤语|英语|日语|韩语|中字|简体|繁体|双语|内嵌|外挂|无字幕)"), " ")
        // 发布组括号 / 末尾 -GROUP
        q = q.replace(Regex("-[A-Za-z0-9]{2,}$"), " ")
        return q
    }

    private fun searchMovie(apiKey: String, query: String, yearHint: String): Match? {
        val url = buildString {
            append(BASE).append("/search/movie?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val picked = pickBestWithReason(arr, query, yearHint, isTv = false) ?: return null
        val (hit, reason) = picked
        val id = hit.optInt("id", 0)
        if (id <= 0) return null
        val detail = getJson("$BASE/movie/$id?api_key=$apiKey&language=zh-CN") ?: return null
        return movieToMatch(detail, hit).copy(confidence = reason)
    }

    private fun searchTv(apiKey: String, query: String, season: String, episode: String, yearHint: String): Match? {
        val url = buildString {
            append(BASE).append("/search/tv?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&first_air_date_year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val picked = pickBestWithReason(arr, query, yearHint, isTv = true) ?: return null
        val (hit, reason) = picked
        val id = hit.optInt("id", 0)
        if (id <= 0) return null
        val detail = getJson("$BASE/tv/$id?api_key=$apiKey&language=zh-CN") ?: return null
        val base = tvToMatch(detail, hit, season, episode).copy(confidence = reason)
        if (season.isBlank() || episode.isBlank()) return base
        val sNum = season.toIntOrNull() ?: return base
        val eNum = episode.toIntOrNull() ?: return base
        val ep = getJson("$BASE/tv/$id/season/$sNum/episode/$eNum?api_key=$apiKey&language=zh-CN") ?: return base
        return base.copy(
            episodeTitle = ep.optString("name", "").trim(),
            episodePlot = ep.optString("overview", "").trim(),
            episodeStillUrl = posterUrl(ep.optString("still_path", "")),
            episodeAirDate = ep.optString("air_date", "").trim(),
        )
    }

    /** 在搜索结果中按年份接近 → 标题完全匹配 → popularity 排序选最优，并返回命中原因。 */
    private fun pickBestWithReason(arr: JSONArray, query: String, yearHint: String, isTv: Boolean): Pair<JSONObject, String>? {
        if (arr.length() == 0) return null
        val qNorm = normalizeForCompare(query)
        val yearTarget = yearHint.toIntOrNull()
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestReason = "popularity"
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = if (isTv) o.optString("name", "") else o.optString("title", "")
            val origTitle = if (isTv) o.optString("original_name", "") else o.optString("original_title", "")
            val date = if (isTv) o.optString("first_air_date", "") else o.optString("release_date", "")
            val popularity = o.optDouble("popularity", 0.0)
            val voteCount = o.optInt("vote_count", 0)
            val yr = date.take(4).toIntOrNull()
            val titleExact = qNorm.isNotEmpty() && (normalizeForCompare(title) == qNorm || normalizeForCompare(origTitle) == qNorm)
            val yearMatch = yearTarget != null && yr != null && yearTarget == yr
            var score = 0.0
            score += if (titleExact) 50.0 else 0.0
            score += if (yearTarget != null && yr != null) (10.0 - kotlin.math.abs(yearTarget - yr).coerceAtMost(10)) else 0.0
            score += kotlin.math.ln(1.0 + popularity)
            score += kotlin.math.ln(1.0 + voteCount) * 0.5
            score -= (i * 0.01)
            if (score > bestScore) {
                bestScore = score
                best = o
                bestReason = when {
                    titleExact && yearMatch -> "title_exact+year_match"
                    titleExact -> "title_exact"
                    yearMatch -> "year_match"
                    else -> "popularity"
                }
            }
        }
        val pick = best ?: arr.optJSONObject(0) ?: return null
        return pick to bestReason
    }

    private fun normalizeForCompare(s: String): String =
        s.lowercase().replace(Regex("[\\s\\p{Punct}]"), "")

    private fun movieToMatch(detail: JSONObject, searchHit: JSONObject): Match {
        val title = detail.optString("title", "").ifBlank { searchHit.optString("title", "") }
        val orig = detail.optString("original_title", "")
        val date = detail.optString("release_date", searchHit.optString("release_date", ""))
        val year = date.take(4)
        val plot = detail.optString("overview", "").trim()
        val genres = genresFromArray(detail.optJSONArray("genres"))
        val poster = posterUrl(detail.optString("poster_path", "").ifBlank { searchHit.optString("poster_path", "") })
        val coll = detail.optJSONObject("belongs_to_collection")?.optString("name", "")?.trim().orEmpty()
        return Match(
            mediaType = "movie",
            tmdbId = detail.optInt("id", 0),
            title = title,
            originalTitle = orig,
            year = year,
            plot = plot,
            genres = genres,
            posterUrl = poster,
            collectionName = coll,
            season = "",
            episode = "",
        )
    }

    private fun tvToMatch(detail: JSONObject, searchHit: JSONObject, season: String, episode: String): Match {
        val title = detail.optString("name", "").ifBlank { searchHit.optString("name", "") }
        val orig = detail.optString("original_name", "")
        val date = detail.optString("first_air_date", searchHit.optString("first_air_date", ""))
        val year = date.take(4)
        val plot = detail.optString("overview", "").trim()
        val genres = genresFromArray(detail.optJSONArray("genres"))
        val poster = posterUrl(detail.optString("poster_path", "").ifBlank { searchHit.optString("poster_path", "") })
        return Match(
            mediaType = "tv",
            tmdbId = detail.optInt("id", 0),
            title = title,
            originalTitle = orig,
            year = year,
            plot = plot,
            genres = genres,
            posterUrl = poster,
            collectionName = title,
            season = season,
            episode = episode,
        )
    }

    private fun genresFromArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private fun posterUrl(path: String): String? {
        val p = path.trim()
        if (p.isBlank() || p == "null") return null
        return IMG_BASE + (if (p.startsWith("/")) p else "/$p")
    }

    private fun getResults(url: String): JSONArray? {
        val root = getJson(url) ?: return null
        return root.optJSONArray("results")
    }

    private fun getJson(url: String): JSONObject? =
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                JSONObject(body)
            }
        }.getOrNull()

    fun downloadPoster(url: String, dest: java.io.File): Boolean =
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val bytes = resp.body?.bytes() ?: return@runCatching false
                if (bytes.size < 512) return@runCatching false
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                true
            }
        }.getOrDefault(false)
}