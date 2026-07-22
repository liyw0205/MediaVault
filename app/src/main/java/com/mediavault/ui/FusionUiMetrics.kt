package com.mediavault.ui

import android.content.Context
import android.util.TypedValue
import com.mediavault.R

/** 横屏融合态：按内容区宽度算网格列数与封面尺寸，避免整屏缩小导致卡片大小不一。 */
object FusionUiMetrics {

    enum class SidebarKind {
        Home,
        Search,
        Collections,
        Scrape,
    }

    fun useFusion(ctx: Context): Boolean = HomeUiPrefs.useTvFusionUi(ctx)

    fun navRailWidthPx(ctx: Context): Int =
        if (useFusion(ctx)) dimenPx(ctx, R.dimen.mv_fusion_nav_rail) else 0

    fun sidebarWidthPx(ctx: Context, kind: SidebarKind): Int {
        if (!useFusion(ctx)) return 0
        val res = when (kind) {
            SidebarKind.Home -> R.dimen.mv_fusion_sidebar_primary
            SidebarKind.Search -> R.dimen.mv_fusion_sidebar_search
            SidebarKind.Collections -> R.dimen.mv_fusion_sidebar_collections
            SidebarKind.Scrape -> R.dimen.mv_fusion_sidebar_scrape
        }
        return dimenPx(ctx, res)
    }

    fun listAreaWidthPx(ctx: Context, sidebarKind: SidebarKind?): Int {
        val dm = ctx.resources.displayMetrics
        val total = dm.widthPixels
        val rail = if (useFusion(ctx)) navRailWidthPx(ctx) else 0
        // 信息/筛选在内容区顶部单列展示，不再占横向侧栏宽度
        val pad = dimenPx(ctx, R.dimen.mv_fusion_content_pad) * 2
        return (total - rail - pad).coerceAtLeast(240)
    }

    fun gridSpanCount(ctx: Context, sidebarKind: SidebarKind?): Int {
        if (!useFusion(ctx)) {
            val cfg = ctx.resources.configuration
            return if (cfg.smallestScreenWidthDp >= 600) 4 else 2
        }
        val w = listAreaWidthPx(ctx, sidebarKind)
        val gap = dimenPx(ctx, R.dimen.mv_fusion_grid_gap)
        val minCell = (118 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(100)
        return (w / (minCell + gap)).coerceIn(4, 10)
    }

    fun collectionTagGridSpan(ctx: Context): Int {
        if (!useFusion(ctx)) return 1
        val w = listAreaWidthPx(ctx, SidebarKind.Collections)
        val minCell = (160 * ctx.resources.displayMetrics.density).toInt()
        return (w / minCell).coerceIn(2, 5)
    }

    fun coverHeightRatio(ctx: Context): Float {
        if (!useFusion(ctx)) return 120f / 160f
        val tv = TypedValue()
        ctx.resources.getValue(R.dimen.mv_fusion_card_cover_ratio, tv, true)
        if (tv.type == TypedValue.TYPE_FLOAT) return tv.float
        return 1.42f
    }

    fun videoCardCellWidthPx(ctx: Context, sidebarKind: SidebarKind?): Int {
        val span = gridSpanCount(ctx, sidebarKind)
        val w = listAreaWidthPx(ctx, sidebarKind)
        val gap = dimenPx(ctx, R.dimen.mv_fusion_grid_gap)
        return ((w - gap * (span - 1)) / span).coerceAtLeast(100)
    }

    fun videoCardCoverHeightPx(ctx: Context, cellWidth: Int): Int {
        val ratio = coverHeightRatio(ctx)
        return (cellWidth * ratio).toInt().coerceAtLeast(if (useFusion(ctx)) 72 else 80)
    }

    private fun dimenPx(ctx: Context, resId: Int): Int =
        ctx.resources.getDimensionPixelSize(resId)
}
