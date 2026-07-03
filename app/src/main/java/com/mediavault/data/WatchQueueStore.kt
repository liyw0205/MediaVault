package com.mediavault.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class WatchQueueStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(
        val path: String,
        val addedAt: Long,
        val updatedAt: Long,
    )

    fun list(): List<Entry> =
        prefs.getString(KEY_ENTRIES, "[]")?.let { decode(it) } ?: emptyList()

    fun paths(): List<String> = list().map { it.path }

    fun contains(path: String): Boolean =
        path.isNotBlank() && list().any { it.path == path }

    fun add(path: String) {
        if (path.isBlank()) return
        val now = System.currentTimeMillis()
        val current = list().filter { it.path != path }.toMutableList()
        current.add(Entry(path = path, addedAt = now, updatedAt = now))
        while (current.size > MAX) current.removeAt(0)
        save(current)
    }

    fun remove(path: String) {
        if (path.isBlank()) return
        save(list().filter { it.path != path })
    }

    fun toggle(path: String): Boolean {
        if (contains(path)) {
            remove(path)
            return false
        }
        add(path)
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(entries: List<Entry>) {
        prefs.edit().putString(KEY_ENTRIES, encode(entries)).apply()
    }

    private fun decode(text: String): List<Entry> = runCatching {
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path", "").trim()
                if (path.isBlank()) continue
                add(
                    Entry(
                        path = path,
                        addedAt = o.optLong("addedAt", 0L),
                        updatedAt = o.optLong("updatedAt", o.optLong("addedAt", 0L)),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("path", entry.path)
                    .put("addedAt", entry.addedAt)
                    .put("updatedAt", entry.updatedAt),
            )
        }
        return arr.toString()
    }

    companion object {
        const val PREFS = "mediavault_watch_queue"
        private const val KEY_ENTRIES = "entries"
        private const val MAX = 200
    }
}
