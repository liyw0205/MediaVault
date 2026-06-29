package com.mediavault.ui

import android.content.Context

object PlaybackPrefs {
    private const val NAME = "playback_ui"
    private const val KEY_AUTOPLAY_MODE = "autoplay_mode"

    enum class AutoplayMode(val storeValue: String) {
        SEQUENTIAL("sequential"),
        REPEAT_ONE("repeat_one"),
        LOOP_COLLECTION("loop_collection"),
        OFF("off");

        companion object {
            fun fromStore(value: String?): AutoplayMode =
                values().firstOrNull { it.storeValue == value } ?: SEQUENTIAL
        }
    }

    fun getAutoplayMode(ctx: Context): AutoplayMode =
        AutoplayMode.fromStore(
            ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_AUTOPLAY_MODE, AutoplayMode.SEQUENTIAL.storeValue),
        )

    fun setAutoplayMode(ctx: Context, mode: AutoplayMode) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTOPLAY_MODE, mode.storeValue)
            .apply()
    }
}
