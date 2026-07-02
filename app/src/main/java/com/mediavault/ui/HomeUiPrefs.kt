package com.mediavault.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * TV 融合态：横屏或 Android TV 类设备时自动启用，无用户手动切换。
 */
object HomeUiPrefs {

    /** 是否使用 TV 融合 UI（疏网格、焦点、横屏主壳等）。 */
    fun useTvFusionUi(ctx: Context): Boolean {
        if (isAndroidTvDevice(ctx)) return true
        return isLandscape(ctx)
    }

    /** @deprecated 使用 [useTvFusionUi] */
    fun isTvLayout(ctx: Context): Boolean = useTvFusionUi(ctx)

    fun isLandscape(ctx: Context): Boolean {
        val o = ctx.resources.configuration.orientation
        return o == Configuration.ORIENTATION_LANDSCAPE
    }

    fun isAndroidTvDevice(ctx: Context): Boolean {
        val ui = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val telephony = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        return ui && !telephony
    }

    fun gridSpanCount(ctx: Context): Int =
        FusionUiMetrics.gridSpanCount(ctx, FusionUiMetrics.SidebarKind.Home)

    fun collectionTagGridSpan(ctx: Context): Int =
        FusionUiMetrics.collectionTagGridSpan(ctx)

    fun coverHeightRatio(ctx: Context): Float = FusionUiMetrics.coverHeightRatio(ctx)
}
