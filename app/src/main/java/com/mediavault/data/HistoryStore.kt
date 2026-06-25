package com.mediavault.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mediavault_history", Context.MODE_PRIVATE)

    fun list(): List<String> = prefs.getString(KEY, "[]")?.let { decode(it) } ?: emptyList()

    fun add(path: String) {
        val cur = list().toMutableList()
        cur.remove(path)
        cur.add(0, path)
        while (cur.size > MAX) cur.removeAt(cur.lastIndex)
        prefs.edit().putString(KEY, encode(cur)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun decode(s: String): List<String> = runCatching {
        val a = JSONArray(s)
        buildList {
            for (i in 0 until a.length()) add(a.optString(i))
        }
    }.getOrDefault(emptyList())

    private fun encode(paths: List<String>): String {
        val a = JSONArray()
        paths.forEach { a.put(it) }
        return a.toString()
    }

    companion object {
        private const val KEY = "paths"
        private const val MAX = 80
    }
}