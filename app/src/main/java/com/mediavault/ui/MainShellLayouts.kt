package com.mediavault.ui

import android.content.Context
import com.mediavault.R

/**
 * 主 Activity 壳：横屏融合显式加载 [activity_main_fusion_land]，
 * 避免部分设备 layout-land qualifier 未命中仍用竖屏底栏、看不到导航轨双按钮。
 */
object MainShellLayouts {

    fun mainActivityLayout(ctx: Context): Int =
        if (HomeUiPrefs.useTvFusionUi(ctx)) R.layout.activity_main_fusion_land
        else R.layout.activity_main
}