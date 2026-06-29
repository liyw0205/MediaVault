package com.mediavault.ui

import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.widget.TextView

object PlotText {
    /** NFO plot 常见 <br> / <br/>，转为可显示换行 */
    fun toSpanned(raw: String): Spanned {
        if (raw.isBlank()) return Html.fromHtml("", Html.FROM_HTML_MODE_LEGACY)
        var s = raw
            .replace(Regex("(?i)<br\\s*/?>"), "<br/>")
            .replace(Regex("(?i)</p>\\s*<p>"), "<br/>")
        if (!s.contains('<')) {
            s = s.replace("\n", "<br/>")
        }
        return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY)
    }

    fun bindPlot(tv: TextView, plot: String, emptyFallback: String) {
        if (plot.isBlank()) {
            tv.text = emptyFallback
            return
        }
        tv.text = toSpanned(plot)
        tv.movementMethod = LinkMovementMethod.getInstance()
    }
}