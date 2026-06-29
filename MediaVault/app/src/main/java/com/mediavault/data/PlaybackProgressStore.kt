package com.mediavault.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 按媒体 path 记住播放进度（毫秒） */
class PlaybackProgressStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mediavault_playback_progress", Context.MODE_PRIVATE)

    fun getPositionMs(path: String): Long {
        if (path.isBlank()) return 0L
        val raw = prefs.getString(key(path), null) ?: return 0L
        return runCatching { JSONObject(raw).optLong("pos", 0L) }.getOrDefault(0L)
    }

    /** 0f..1f，无记录或已看完返回 null */
    fun getFraction(path: String): Float? {
        if (path.isBlank()) return null
        val raw = prefs.getString(key(path), null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val pos = o.optLong("pos", 0L)
        val dur = o.optLong("dur", 0L)
        if (pos < MIN_SAVE_MS) return null
        if (dur > 0 && pos >= dur - END_MARGIN_MS) return null
        return if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
    }

    fun save(path: String, positionMs: Long, durationMs: Long = 0L) {
        if (path.isBlank()) return
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        // 接近片尾视为看完，下次从头
        if (dur > 0 && pos >= dur - END_MARGIN_MS) {
            prefs.edit().remove(key(path)).apply()
            return
        }
        if (pos < MIN_SAVE_MS) {
            prefs.edit().remove(key(path)).apply()
            return
        }
        val o = JSONObject()
            .put("pos", pos)
            .put("dur", dur)
            .put("at", System.currentTimeMillis())
        prefs.edit().putString(key(path), o.toString()).apply()
    }

    fun clear(path: String) {
        prefs.edit().remove(key(path)).apply()
    }

    private fun key(path: String) = "p_${path.hashCode()}_${path.length}"

    companion object {
        private const val MIN_SAVE_MS = 3_000L
        private const val END_MARGIN_MS = 15_000L
    }
}