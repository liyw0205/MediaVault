package com.mediavault.ui

import android.icu.text.Transliterator

/**
 * 标题简拼：将标题转成拉丁后取各词首字母，供搜索框匹配（如「三体」→ st）。
 */
object SearchPinyin {
    private val toLatin: Transliterator? by lazy {
        try {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        } catch (_: Exception) {
            null
        }
    }

    fun compactInitials(text: String): String {
        if (text.isBlank()) return ""
        val latin = toLatin?.transliterate(text) ?: text
        val lowered = latin.lowercase()
        val sb = StringBuilder()
        var wantInitial = true
        for (ch in lowered) {
            when {
                ch.isLetter() -> {
                    if (wantInitial) {
                        sb.append(ch)
                        wantInitial = false
                    }
                }
                ch.isWhitespace() || ch == '-' || ch == '_' || ch == '.' -> wantInitial = true
                else -> wantInitial = true
            }
        }
        return sb.toString()
    }
}