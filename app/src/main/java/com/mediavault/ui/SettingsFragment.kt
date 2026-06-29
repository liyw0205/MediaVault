package com.mediavault.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.mediavault.MediaVaultApp
import com.mediavault.R

/**
 * 应用设置：数据清理入口、默认连播模式（与播放器列表内设置共用 PlaybackPrefs）。
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = activity as? AppCompatActivity ?: return
        val repo = (act.application as MediaVaultApp).repository

        view.findViewById<View>(R.id.settingsOpenDataStorage).setOnClickListener {
            DataStorageDialog.show(act, repo) {
                (act as? MainActivity)?.refreshHome(recommendPathsOnly = false)
            }
        }

        view.findViewById<View>(R.id.settingsOpenScrapeDrawer).setOnClickListener {
            (act as? MainActivity)?.openScrapeSettingsFromSettingsTab()
        }

        val hint = view.findViewById<TextView>(R.id.settingsDataHint)
        hint.setText(R.string.settings_data_hint)

        val group = view.findViewById<RadioGroup>(R.id.settingsAutoplayGroup)
        val modes = listOf(
            PlaybackPrefs.AutoplayMode.SEQUENTIAL to R.id.settingsAutoplaySequential,
            PlaybackPrefs.AutoplayMode.REPEAT_ONE to R.id.settingsAutoplayRepeatOne,
            PlaybackPrefs.AutoplayMode.LOOP_COLLECTION to R.id.settingsAutoplayLoop,
            PlaybackPrefs.AutoplayMode.OFF to R.id.settingsAutoplayOff,
        )
        val current = PlaybackPrefs.getAutoplayMode(act)
        modes.forEach { (mode, id) ->
            view.findViewById<RadioButton>(id).isChecked = mode == current
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = modes.firstOrNull { it.second == checkedId }?.first ?: return@setOnCheckedChangeListener
            PlaybackPrefs.setAutoplayMode(act, mode)
        }
    }
}