package com.mediavault.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 左/右半屏长按：倍速（松手恢复）；长按后横向拖动：取消倍速，松手再 seek。
 * 左/中/右双击：快退 / 暂停·播放 / 快进。
 */
class PlayerGestureController(
    private val root: View,
    private val playerProvider: () -> ExoPlayer?,
    private val chrome: PlayerChromeController,
    private val toast: (String) -> Unit,
) {
    private var speedActive = false
    private var dragSeeking = false
    private var dragStartX = 0f
    private var dragStartPos = 0L
    private var dragPreviewPos = 0L
    private val longPressMs = 280L
    private var pendingSpeed: Runnable? = null
    private var zone = -1
    private var speedHintShown = false

    private val speedRate = 2f

    private val detector = GestureDetector(
        root.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width.coerceAtLeast(1)
                val x = e.x
                val p = playerProvider() ?: return true
                chrome.onUserGesture()
                val dur = p.duration.coerceAtLeast(0L)
                when {
                    x < w / 3f -> {
                        val target = (p.currentPosition - 10_000).coerceIn(0L, dur)
                        p.seekTo(target)
                        val t = PlayerTimeFormat.formatMs(target)
                        chrome.showCenterHint(t)
                        root.postDelayed({ chrome.hideSeekOverlay() }, 600)
                        toast(t)
                    }
                    x > w * 2f / 3f -> {
                        val target = (p.currentPosition + 10_000).coerceIn(0L, dur)
                        p.seekTo(target)
                        val t = PlayerTimeFormat.formatMs(target)
                        chrome.showCenterHint(t)
                        root.postDelayed({ chrome.hideSeekOverlay() }, 600)
                        toast(t)
                    }
                    else -> {
                        p.playWhenReady = !p.playWhenReady
                        // 暂停/播放不堆文字，仅切换状态
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                chrome.toggleChrome()
                return true
            }
        },
    )

    fun attach() {
        root.setOnTouchListener { v, event ->
            val w = v.width.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPending()
                    dragSeeking = false
                    speedHintShown = false
                    zone = when {
                        event.x < w / 3f -> 0
                        event.x > w * 2f / 3f -> 2
                        else -> 1
                    }
                    if (zone == 0 || zone == 2) {
                        val r = Runnable { startSpeed() }
                        pendingSpeed = r
                        root.postDelayed(r, longPressMs)
                    } else if (zone == 1) {
                        val r = Runnable { beginCenterDragSeek() }
                        pendingSpeed = r
                        root.postDelayed(r, longPressMs)
                    }
                    dragStartX = event.x
                    dragStartPos = playerProvider()?.currentPosition ?: 0L
                    dragPreviewPos = dragStartPos
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(event.x - dragStartX) > 20
                    if (zone == 0 || zone == 2) {
                        if (speedActive && moved && !dragSeeking) {
                            restoreSpeed()
                            dragSeeking = true
                        }
                        if (pendingSpeed != null && moved) {
                            cancelPending()
                            if (speedActive) restoreSpeed()
                            dragSeeking = true
                        }
                        if (dragSeeking) previewScrub(event.x, w)
                    } else if (zone == 1) {
                        if (pendingSpeed != null && moved) {
                            cancelPending()
                            dragSeeking = true
                        }
                        if (dragSeeking) previewScrub(event.x, w)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPending()
                    if (speedActive) restoreSpeed()
                    if (dragSeeking) {
                        chrome.commitGestureSeek(dragPreviewPos)
                    }
                    dragSeeking = false
                    zone = -1
                }
            }
            detector.onTouchEvent(event)
            true
        }
    }

    private fun beginCenterDragSeek() {
        pendingSpeed = null
        dragSeeking = true
        dragPreviewPos = dragStartPos
        chrome.onUserGesture()
    }

    private fun previewScrub(x: Float, w: Int) {
        val dur = playerProvider()?.duration?.coerceAtLeast(1L) ?: return
        val delta = ((x - dragStartX) / w * dur * 0.4f).toLong()
        dragPreviewPos = (dragStartPos + delta).coerceIn(0L, dur)
        chrome.previewGestureSeek(dragPreviewPos)
        chrome.onUserGesture()
    }

    private fun startSpeed() {
        pendingSpeed = null
        val p = playerProvider() ?: return
        speedActive = true
        p.playbackParameters = p.playbackParameters.withSpeed(speedRate)
        val label = formatSpeedLabel(speedRate)
        chrome.showCenterHint(label)
        if (!speedHintShown) {
            speedHintShown = true
            toast(label)
        }
        chrome.onUserGesture()
    }

    private fun restoreSpeed() {
        val p = playerProvider() ?: return
        speedActive = false
        p.playbackParameters = p.playbackParameters.withSpeed(1f)
        if (!dragSeeking) chrome.hideSeekOverlay()
    }

    private fun formatSpeedLabel(rate: Float): String {
        val r = (rate * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) "${r.toInt()}x" else "${r}x"
    }

    private fun cancelPending() {
        pendingSpeed?.let { root.removeCallbacks(it) }
        pendingSpeed = null
    }
}