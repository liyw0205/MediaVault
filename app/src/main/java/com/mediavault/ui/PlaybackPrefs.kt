package com.mediavault.ui

import android.content.Context
import com.mediavault.R

object PlaybackPrefs {
    private const val NAME = "playback_ui"
    private const val KEY_AUTOPLAY_MODE = "autoplay_mode"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_AUDIO_MATCH = "audio_match"
    private const val KEY_AUDIO_LABEL = "audio_label"
    private const val KEY_AUDIO_LANGUAGE = "audio_language"

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

    enum class AudioMode(val storeValue: String) {
        AUTO("auto"),
        MANUAL("manual");

        companion object {
            fun fromStore(value: String?): AudioMode =
                values().firstOrNull { it.storeValue == value } ?: AUTO
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

    fun label(ctx: Context, mode: AutoplayMode = getAutoplayMode(ctx)): String = when (mode) {
        AutoplayMode.SEQUENTIAL -> ctx.getString(R.string.player_autoplay_sequential)
        AutoplayMode.REPEAT_ONE -> ctx.getString(R.string.player_autoplay_repeat_one)
        AutoplayMode.LOOP_COLLECTION -> ctx.getString(R.string.player_autoplay_loop_collection)
        AutoplayMode.OFF -> ctx.getString(R.string.player_autoplay_off)
    }

    fun getAudioMode(ctx: Context): AudioMode =
        AudioMode.fromStore(
            ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_AUDIO_MODE, AudioMode.AUTO.storeValue),
        )

    fun getAudioMatch(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_MATCH, null)
            ?.takeIf { it.isNotBlank() }

    fun getAudioLabel(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_LABEL, null)
            ?.takeIf { it.isNotBlank() }

    fun getAudioLanguage(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUDIO_LANGUAGE, null)
            ?.takeIf { it.isNotBlank() }

    fun setAudioAuto(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUDIO_MODE, AudioMode.AUTO.storeValue)
            .remove(KEY_AUDIO_MATCH)
            .remove(KEY_AUDIO_LABEL)
            .remove(KEY_AUDIO_LANGUAGE)
            .apply()
    }

    fun setAudioManual(ctx: Context, match: String, label: String, language: String?) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUDIO_MODE, AudioMode.MANUAL.storeValue)
            .putString(KEY_AUDIO_MATCH, match)
            .putString(KEY_AUDIO_LABEL, label)
            .putString(KEY_AUDIO_LANGUAGE, language.orEmpty())
            .apply()
    }
}
