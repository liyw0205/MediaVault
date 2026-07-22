package com.mediavault.ui

import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.navigationrail.NavigationRailView
import com.mediavault.R

/** 横屏：仅导航轨 + 各 Tab 内「信息区 + 媒体区」一体单列（无轨旁折叠按钮）。 */
object FusionLandscapeShell {

    fun wireMainActivity(activity: MainActivity) {
        if (!HomeUiPrefs.useTvFusionUi(activity)) return
        val navWrap = activity.findViewById<View>(R.id.fusionNavRailWrap) ?: return
        applyNavRailWidth(activity, navWrap)
        activity.findViewById<NavigationRailView>(R.id.navRail)?.isFocusable = true
    }

    private fun applyNavRailWidth(activity: MainActivity, wrap: View) {
        val w = activity.resources.getDimensionPixelSize(R.dimen.mv_fusion_nav_rail)
        val lp = wrap.layoutParams
        if (lp.width != w) {
            lp.width = w
            wrap.layoutParams = lp
        }
    }

    fun applyFragmentRoot(root: View?, @Suppress("UNUSED_PARAMETER") sidebarKind: FusionUiMetrics.SidebarKind) {
        if (root == null) return
        val ctx = root.context
        if (!HomeUiPrefs.useTvFusionUi(ctx)) return

        val meta = root.findViewById<View>(R.id.fusionMetaPanel)
        if (meta != null) {
            meta.isVisible = true
            val lp = meta.layoutParams as? LinearLayout.LayoutParams
            if (lp != null) {
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                meta.layoutParams = lp
            }
        }
        val tags = root.findViewById<View>(R.id.fusionTagInfoPanel)
        if (tags != null) {
            tags.isVisible = true
        }
        root.findViewById<View>(R.id.fusionMediaPanel)?.requestLayout()
    }
}