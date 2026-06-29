package com.mediavault.scrape

import android.content.Context
import com.mediavault.R

object ScrapeProgressFormat {
    private const val FILE_MAX_CHARS = 28

    fun countLine(ctx: Context, scraped: Int, libraryTotal: Int): String =
        ctx.getString(R.string.scrape_progress_count, scraped, libraryTotal)

    /** 收起浮窗：仅「本批 / 库总量」 */
    fun collapsedCompact(scraped: Int, libraryTotal: Int): String = "$scraped / $libraryTotal"

    /** 队列进度：远程/本地 已处理/待处理 */
    fun queueLine(ctx: Context, scope: String, done: Int, total: Int): String {
        if (total <= 0) return scope.ifBlank { ctx.getString(R.string.scrape_progress_queue_unknown) }
        return ctx.getString(R.string.scrape_progress_queue_fmt, scope, done, total)
    }

    fun queuePercent(done: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((done.coerceAtMost(total)).toLong() * 100L / total).toInt().coerceIn(0, 100)
    }

    fun ellipsizeFileName(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return ""
        if (t.length <= FILE_MAX_CHARS) return t
        val head = 12
        val tail = 10
        return t.take(head) + "…" + t.takeLast(tail)
    }

    fun isPrepStatus(message: String): Boolean {
        val m = message.trim()
        if (m.isEmpty()) return false
        val markers = listOf(
            "收集", "待处理", "枚举", "准备", "启动", "扫描 ", "目录遍历",
            "远程扫描", "没有需要", "待扫", "遍历",
        )
        return markers.any { m.contains(it) } && !m.startsWith("已刮削")
    }

    /** 从旧版 status 文案里抽出文件名（「·」后一段），或整段即为文件名 */
    fun fileFromLegacyMessage(message: String): String {
        val m = message.trim()
        if (m.isEmpty() || isPrepStatus(m)) return ""
        val idx = m.lastIndexOf('·')
        if (idx >= 0 && idx < m.length - 1) {
            return m.substring(idx + 1).trim()
        }
        if (m.contains("已刮削") || m.contains("已入库") || m.contains("待处理") || m.contains("收集")) {
            return ""
        }
        return m
    }
}