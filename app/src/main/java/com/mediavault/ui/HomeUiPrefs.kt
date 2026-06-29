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

    fun gridSpanCount(ctx: Context): Int {
        val fusion = useTvFusionUi(ctx)
        val wide = ctx.resources.configuration.smallestScreenWidthDp >= 600
        return when {
            fusion && wide -> 4
            fusion -> 3
            wide -> 4
            else -> 2
        }
    }

    fun coverHeightRatio(fusion: Boolean): Float = if (fusion) 0.75f else 120f / 180f
}