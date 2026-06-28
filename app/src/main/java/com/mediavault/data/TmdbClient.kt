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
    )

    fun lookup(apiKey: String, fileName: String, seasonHint: String, episodeHint: String, yearHint: String): Match? {
        val key = apiKey.trim()
        if (key.isBlank()) return null
        val clean = fileName.substringBeforeLast('.')
        val query = queryFromFilename(clean)
        if (query.length < 2) return null
        val isTv = seasonHint.isNotBlank() || episodeHint.isNotBlank() ||
            Regex("(?i)s\\d{1,2}e\\d{1,3}").containsMatchIn(clean)
        return if (isTv) {
            searchTv(key, query, seasonHint, episodeHint, yearHint)
                ?: searchMovie(key, query, yearHint)
        } else {
            searchMovie(key, query, yearHint)
                ?: searchTv(key, query, seasonHint, episodeHint, yearHint)
        }
    }

    private fun queryFromFilename(clean: String): String {
        var q = clean
        q = Regex("(?i)\\s*s\\d{1,2}e\\d{1,3}.*").replace(q, "")
        q = Regex("\\(\\d{4}\\)").replace(q, "")
        q = Regex("(?:^|[^0-9])(19|20)\\d{2}(?:[^0-9]|$)").replace(q, " ")
        q = q.replace(Regex("[\\[\\]【】._\\-]+"), " ")
        return q.trim().replace(Regex("\\s+"), " ")
    }

    private fun searchMovie(apiKey: String, query: String, yearHint: String): Match? {
        val url = buildString {
            append(BASE).append("/search/movie?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val hit = arr.optJSONObject(0) ?: return null
        val id = hit.optInt("id", 0)
        if (id <= 0) return null
        val detail = getJson("$BASE/movie/$id?api_key=$apiKey&language=zh-CN") ?: return null
        return movieToMatch(detail, hit)
    }

    private fun searchTv(apiKey: String, query: String, season: String, episode: String, yearHint: String): Match? {
        val url = buildString {
            append(BASE).append("/search/tv?api_key=").append(apiKey)
            append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&language=zh-CN")
            if (yearHint.isNotBlank()) append("&first_air_date_year=").append(yearHint)
        }
        val arr = getResults(url) ?: return null
        val hit = arr.optJSONObject(0) ?: return null
        val id = hit.optInt("id", 0)
        if (id <= 0) return null
        val detail = getJson("$BASE/tv/$id?api_key=$apiKey&language=zh-CN") ?: return null
        return tvToMatch(detail, hit, season, episode)
    }

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