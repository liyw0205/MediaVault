package com.mediavault.data

import android.content.Context

/** 播放器自动字幕与刮削侧车排序共用的语言偏好。 */
object SubtitlePrefs {
    private const val NAME = "subtitle_prefs"
    private const val KEY_PRIMARY = "primary_lang"
    private const val KEY_PERSISTED_MODE = "persisted_mode"
    private const val KEY_MANUAL_TIER = "manual_tier"

    enum class PrimaryLang(val store: String) {
        HANS_FIRST("hans_first"),
        HANT_FIRST("hant_first"),
        EN_FIRST("en_first"),
        NEUTRAL("neutral");

        companion object {
            fun fromStore(v: String?): PrimaryLang =
                entries.firstOrNull { it.store == v } ?: HANS_FIRST
        }
    }

    /** 跨集记忆：用户在字幕菜单里选过「自动 / 关 / 某类语言轨」后，换集仍按同一策略。 */
    enum class PersistedMode(val store: String) {
        AUTO("auto"),
        OFF("off"),
        MANUAL_TIER("manual_tier");

        companion object {
            fun fromStore(v: String?): PersistedMode =
                entries.firstOrNull { it.store == v } ?: AUTO
        }
    }

    fun getPrimary(ctx: Context): PrimaryLang =
        PrimaryLang.fromStore(prefs(ctx).getString(KEY_PRIMARY, PrimaryLang.HANS_FIRST.store))

    fun setPrimary(ctx: Context, lang: PrimaryLang) {
        prefs(ctx).edit().putString(KEY_PRIMARY, lang.store).apply()
    }

    fun getPersistedMode(ctx: Context): PersistedMode =
        PersistedMode.fromStore(prefs(ctx).getString(KEY_PERSISTED_MODE, PersistedMode.AUTO.store))

    /** 与 [SubtitleTrackRanker.Tier] 的 ordinal 一致，仅 MANUAL_TIER 时有效。 */
    fun getManualTier(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MANUAL_TIER, SubtitleTrackRanker.Tier.HANS.ordinal)

    fun setPersistedAuto(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_PERSISTED_MODE, PersistedMode.AUTO.store)
            .apply()
    }

    fun setPersistedOff(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_PERSISTED_MODE, PersistedMode.OFF.store)
            .apply()
    }

    fun setPersistedManualTier(ctx: Context, tier: SubtitleTrackRanker.Tier) {
        prefs(ctx).edit()
            .putString(KEY_PERSISTED_MODE, PersistedMode.MANUAL_TIER.store)
            .putInt(KEY_MANUAL_TIER, tier.ordinal)
            .apply()
    }

    fun sortSubtitlePaths(ctx: Context, paths: List<String>): List<String> {
        if (paths.size <= 1) return paths
        if (getPrimary(ctx) == PrimaryLang.NEUTRAL) return paths
        val primary = getPrimary(ctx)
        return paths.sortedBy { SubtitleTrackRanker.rankPathTokens(it, primary) }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

object SubtitleTrackRanker {
    enum class Tier(val rank: Int) {
        HANS(0),
        HANT(1),
        ZH(2),
        EN(3),
        OTHER(4);

        companion object {
            fun fromOrdinal(o: Int): Tier = entries.getOrElse(o) { HANS }
        }
    }

    fun tierFromTokenString(token: String): Tier {
        val t = token.lowercase()
        return when {
            t.contains("zh-hans") || t.contains("zh-cn") || t.contains("zhs") ||
                t.contains("简体") || t.contains("简中") || t.contains("chs") ||
                t.contains(".chs.") || t.contains(".sc.") -> Tier.HANS
            t.contains("zh-hant") || t.contains("zh-tw") || t.contains("zh-hk") || t.contains("zht") ||
                t.contains("繁体") || t.contains("繁中") || t.contains("cht") ||
                t.contains(".cht.") || t.contains(".tc.") -> Tier.HANT
            t.startsWith("zh") || t.contains("chinese") || t.contains("中文") ||
                t.contains(".cn.") || t.contains(".zh.") -> Tier.ZH
            t.startsWith("en") || t.contains("english") || t.contains(".eng.") ||
                t.contains(".en.") -> Tier.EN
            else -> Tier.OTHER
        }
    }

    fun rankTrackTokens(lang: String?, label: String?, id: String?, primary: SubtitlePrefs.PrimaryLang): Int {
        val token = "${lang.orEmpty()} ${label.orEmpty()} ${id.orEmpty()}"
        return rankTier(tierFromTokenString(token), primary)
    }

    fun rankPathTokens(path: String, primary: SubtitlePrefs.PrimaryLang): Int {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return rankTier(tierFromTokenString(name), primary)
    }

    private fun rankTier(tier: Tier, primary: SubtitlePrefs.PrimaryLang): Int {
        val base = tier.rank * 10
        val bias = when (primary) {
            SubtitlePrefs.PrimaryLang.HANS_FIRST -> when (tier) {
                Tier.HANS -> -3
                Tier.HANT -> 1
                Tier.EN -> 2
                else -> 0
            }
            SubtitlePrefs.PrimaryLang.HANT_FIRST -> when (tier) {
                Tier.HANT -> -3
                Tier.HANS -> 1
                Tier.EN -> 2
                else -> 0
            }
            SubtitlePrefs.PrimaryLang.EN_FIRST -> when (tier) {
                Tier.EN -> -3
                Tier.HANS -> 1
                Tier.HANT -> 1
                else -> 0
            }
            SubtitlePrefs.PrimaryLang.NEUTRAL -> 0
        }
        return base + bias
    }
}