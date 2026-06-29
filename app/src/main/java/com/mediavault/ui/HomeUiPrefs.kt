package com.mediavault.ui

import android.content.Context
import android.content.pm.PackageManager

object HomeUiPrefs {
    private const val PREFS = "home_ui"
    private const val KEY_LAYOUT = "layout_mode"

    const val LAYOUT_PHONE = "phone"
    const val LAYOUT_TV = "tv"

    fun isTvLayout(ctx: Context): Boolean {
        if (isAndroidTvDevice(ctx)) return true
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT, LAYOUT_PHONE) == LAYOUT_TV
    }

    fun setTvLayout(ctx: Context, tv: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT, if (tv) LAYOUT_TV else LAYOUT_PHONE)
            .apply()
    }

    fun isAndroidTvDevice(ctx: Context): Boolean {
        val ui = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val telephony = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        return ui && !telephony
    }

    fun canToggleLayout(ctx: Context): Boolean = !isAndroidTvDevice(ctx)
}