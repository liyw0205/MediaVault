package com.mediavault.ui

import android.content.Context
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** 横屏融合态下标签/筛选 Chip 更紧凑，便于多行展示。 */
object FusionTagLayoutHelper {

    fun applyFusionChipGroup(group: ChipGroup, fusion: Boolean, singleLine: Boolean = false) {
        val h = if (fusion) 2.dp(group.context) else 4.dp(group.context)
        val v = if (fusion) 1.dp(group.context) else 4.dp(group.context)
        group.setChipSpacingHorizontal(h)
        group.setChipSpacingVertical(v)
        group.isSingleLine = singleLine || !fusion
        if (singleLine) {
            group.isSingleLine = true
        }
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
