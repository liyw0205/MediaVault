package com.mediavault.ui

import android.content.Context
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** 融合态下标签/筛选 Chip 更紧凑；是否单行由调用方显式指定。 */
object FusionTagLayoutHelper {

    fun applyFusionChipGroup(group: ChipGroup, fusion: Boolean, singleLine: Boolean = false) {
        val h = if (fusion) 2.dp(group.context) else 4.dp(group.context)
        val v = if (fusion) 1.dp(group.context) else 4.dp(group.context)
        group.setChipSpacingHorizontal(h)
        group.setChipSpacingVertical(v)
        // 以前写成 singleLine || !fusion，竖屏永远单行 → 标签不会向下换行
        group.isSingleLine = singleLine
    }

    fun styleTagChip(chip: Chip, fusion: Boolean) {
        if (!fusion) return
        chip.textSize = 11f
        chip.chipMinHeight = 28f
        chip.chipStartPadding = 6f
        chip.chipEndPadding = 6f
        chip.minHeight = 0
    }

    fun styleFilterChip(chip: Chip, fusion: Boolean) {
        if (!fusion) return
        chip.textSize = 11f
        chip.chipMinHeight = 28f
        chip.chipStartPadding = 6f
        chip.chipEndPadding = 6f
        chip.minHeight = 0
    }

    private fun Int.dp(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()
}
