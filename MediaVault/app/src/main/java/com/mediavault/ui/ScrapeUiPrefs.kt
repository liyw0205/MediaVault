package com.mediavault.ui

import android.content.Context

object ScrapeUiPrefs {
    private const val NAME = "scrape_ui"
    private const val KEY_OVERLAY_EXPANDED = "progress_overlay_expanded"

    fun isProgressOverlayExpanded(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_EXPANDED, true)

    fun setProgressOverlayExpanded(ctx: Context, expanded: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OVERLAY_EXPANDED, expanded)
            .apply()
    }
}