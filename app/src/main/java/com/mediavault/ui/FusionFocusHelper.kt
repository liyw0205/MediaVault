package com.mediavault.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout

/** 融合态：顶栏与侧栏控件可 D-pad 聚焦。 */
object FusionFocusHelper {

    fun applyFusionToolbarFocus(root: View?) {
        if (root == null) return
        if (!HomeUiPrefs.useTvFusionUi(root.context)) return
        walkFocusable(root)
    }

    private fun walkFocusable(v: View) {
        when (v) {
            is MaterialButton, is ImageButton, is Chip -> {
                v.isFocusable = true
                v.isFocusableInTouchMode = true
            }
            is TabLayout -> {
                for (i in 0 until v.tabCount) {
                    v.getTabAt(i)?.view?.let { tab ->
                        tab.isFocusable = true
                        tab.isFocusableInTouchMode = true
                    }
                }
            }
            is ViewGroup -> {
                for (i in 0 until v.childCount) walkFocusable(v.getChildAt(i))
            }
        }
    }
}
