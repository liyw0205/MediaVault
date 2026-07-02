package com.mediavault.ui

import android.content.Context

/** 横屏融合布局预留（折叠按钮已移除；保留文件避免旧引用编译失败）。 */
object FusionLandscapePrefs {
    private const val P = "fusion_landscape"

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun isNavRailExpanded(ctx: Context): Boolean = true

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun setNavRailExpanded(ctx: Context, expanded: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("nav_expanded", expanded).apply()
    }

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun isInfoPanelExpanded(ctx: Context): Boolean = true

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun setInfoPanelExpanded(ctx: Context, expanded: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("info_expanded", expanded).apply()
    }

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun isTagInfoExpanded(ctx: Context): Boolean = true

    @Deprecated("折叠按钮已移除", level = DeprecationLevel.HIDDEN)
    fun setTagInfoExpanded(ctx: Context, expanded: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("tags_expanded", expanded).apply()
    }
}