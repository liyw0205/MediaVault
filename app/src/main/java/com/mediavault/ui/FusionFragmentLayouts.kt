package com.mediavault.ui

import android.content.Context

/**
 * 横屏 / TV 融合态选用专用 layout（与手机版 id 一致，Fragment 逻辑共用）。
 */
object FusionFragmentLayouts {

    fun home(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.fragment_home_land else R.layout.fragment_home

    fun search(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.fragment_search_land else R.layout.fragment_search

    fun collections(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.fragment_collections_land else R.layout.fragment_collections

    fun scrape(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.fragment_scrape_land else R.layout.fragment_scrape

    fun player(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.activity_player_land else R.layout.activity_player
}