package com.mediavault.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import com.mediavault.R

object PlayerTimeFormat {
    fun formatMs(ms: Long): String {
        if (ms < 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }
}

class PlayerChromeController(
    private val activity: AppCompatActivity,
    private val playerProvider: () -> ExoPlayer?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var chromeVisible = false
    private var userScrubbing = false
    private var autoHideRunnable: Runnable? = null

    private lateinit var titleOverlay: TextView
    private lateinit var bottomChrome: View
    private lateinit var seekExpanded: SeekBar
    private lateinit var seekImmersive: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var seekCenter: TextView

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!userScrubbing) syncProgressFromPlayer()
            handler.postDelayed(this, 500)
        }
    }

    fun bind(root: View) {
        titleOverlay = root.findViewById(R.id.playerTitleOverlay)
        bottomChrome = root.findViewById(R.id.playerBottomChrome)
        seekExpanded = root.findViewById(R.id.playerSeekBar)
        seekImmersive = root.findViewById(R.id.playerSeekBarImmersive)
        timeCurrent = root.findViewById(R.id.playerTimeCurrent)
        timeTotal = root.findViewById(R.id.playerTimeTotal)
        seekCenter = root.findViewById(R.id.playerSeekCenterOverlay)

        val scrubListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = durationMs()
                val pos = (progress / 1000.0 * dur).toLong().coerceIn(0L, dur)
                previewPositionMs(pos, dur, showCenter = true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userScrubbing = true
                cancelAutoHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userScrubbing = false
                val dur = durationMs()
                val progress = seekBar?.progress ?: 0
                val pos = (progress / 1000.0 * dur).toLong().coerceIn(0L, dur)
                hideSeekOverlay()
                playerProvider()?.seekTo(pos)
                syncProgressFromPlayer()
                scheduleAutoHide()
            }
        }
        seekExpanded.setOnSeekBarChangeListener(scrubListener)
        seekImmersive.setOnSeekBarChangeListener(scrubListener)

        enterImmersiveChromeHidden()
        handler.post(tickRunnable)
    }

    fun toggleChrome() {
        if (chromeVisible) enterImmersiveChromeHidden() else showChrome()
    }

    fun showChrome() {
        chromeVisible = true
        titleOverlay.visibility = View.VISIBLE
        bottomChrome.visibility = View.VISIBLE
        seekImmersive.visibility = View.GONE
        hideSystemBars(false)
        syncProgressFromPlayer()
        scheduleAutoHide()
    }

    fun enterImmersiveChromeHidden() {
        chromeVisible = false
        titleOverlay.visibility = View.GONE
        bottomChrome.visibility = View.GONE
        seekImmersive.visibility = View.VISIBLE
        hideSystemBars(true)
        cancelAutoHide()
        syncProgressFromPlayer()
    }

    /** 拖动进度时只更新 UI，不 seek */
    fun previewGestureSeek(positionMs: Long) {
        val dur = durationMs().coerceAtLeast(1L)
        val pos = positionMs.coerceIn(0L, dur)
        userScrubbing = true
        previewPositionMs(pos, dur, showCenter = true)
    }

    fun commitGestureSeek(positionMs: Long) {
        userScrubbing = false
        hideSeekOverlay()
        val dur = durationMs()
        val pos = positionMs.coerceIn(0L, dur.coerceAtLeast(0L))
        playerProvider()?.seekTo(pos)
        syncProgressFromPlayer()
    }

    fun showCenterHint(text: String) {
        seekCenter.text = text
        seekCenter.visibility = View.VISIBLE
    }

    fun hideSeekOverlay() {
        seekCenter.visibility = View.GONE
    }

    fun onUserGesture() {
        if (chromeVisible) scheduleAutoHide()
    }

    private fun previewPositionMs(pos: Long, dur: Long, showCenter: Boolean) {
        timeCurrent.text = PlayerTimeFormat.formatMs(pos)
        if (dur > 0) {
            val prog = (pos * 1000.0 / dur).toInt().coerceIn(0, 1000)
            mirrorSeekProgress(prog)
        }
        if (showCenter) {
            seekCenter.text = PlayerTimeFormat.formatMs(pos)
            seekCenter.visibility = View.VISIBLE
        }
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        val r = Runnable { enterImmersiveChromeHidden() }
        autoHideRunnable = r
        handler.postDelayed(r, 4000)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    private fun hideSystemBars(immersive: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val c = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (immersive) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun durationMs(): Long {
        val d = playerProvider()?.duration ?: 0L
        return if (d > 0) d else 0L
    }

    private fun syncProgressFromPlayer() {
        val p = playerProvider() ?: return
        val dur = p.duration
        val pos = p.currentPosition
        timeTotal.text = PlayerTimeFormat.formatMs(dur)
        timeCurrent.text = PlayerTimeFormat.formatMs(pos)
        if (dur > 0) {
            val prog = (pos * 1000.0 / dur).toInt().coerceIn(0, 1000)
            seekExpanded.progress = prog
            seekImmersive.progress = prog
        } else {
            seekExpanded.progress = 0
            seekImmersive.progress = 0
        }
    }

    private fun mirrorSeekProgress(progress: Int) {
        if (seekExpanded.progress != progress) seekExpanded.progress = progress
        if (seekImmersive.progress != progress) seekImmersive.progress = progress
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        cancelAutoHide()
    }
}