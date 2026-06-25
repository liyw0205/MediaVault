package com.mediavault.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * 对齐 Neribox 文件管理播放器：
 * - 左/右半屏长按：前进倍速（松手恢复）
 * - 长按后横向拖：取消倍速，按位移 seek
 * - 左/右双击：快退/快进 10s；中间双击：暂停/播放
 */
class PlayerGestureController(
    private val root: View,
    private val playerProvider: () -> ExoPlayer?,
    private val onUserInteraction: () -> Unit,
) {
    private var speedActive = false
    private var dragSeeking = false
    private var dragStartX = 0f
    private var dragStartPos = 0L
    private val longPressMs = 50L
    private var pendingSpeed: Runnable? = null
    private var zone = -1 // 0 left 1 center 2 right

    private val speedSteps = floatArrayOf(2f, 3f, 4f)

    private val detector = GestureDetector(
        root.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width.coerceAtLeast(1)
                val x = e.x
                val p = playerProvider() ?: return true
                onUserInteraction()
                when {
                    x < w / 3f -> seekBy(p, -10_000)
                    x > w * 2f / 3f -> seekBy(p, 10_000)
                    else -> {
                        p.playWhenReady = !p.playWhenReady
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onUserInteraction()
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
                    zone = when {
                        event.x < w / 3f -> 0
                        event.x > w * 2f / 3f -> 2
                        else -> 1
                    }
                    if (zone == 0 || zone == 2) {
                        val r = Runnable { startSpeed() }
                        pendingSpeed = r
                        root.postDelayed(r, longPressMs)
                    }
                    dragStartX = event.x
                    dragStartPos = playerProvider()?.currentPosition ?: 0L
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((zone == 0 || zone == 2) && pendingSpeed != null && abs(event.x - dragStartX) > 24) {
                        cancelPending()
                        if (speedActive) restoreSpeed()
                        dragSeeking = true
                        val p = playerProvider() ?: return@setOnTouchListener detector.onTouchEvent(event)
                        val dur = p.duration.coerceAtLeast(1L)
                        val delta = ((event.x - dragStartX) / w * dur * 0.35f).toLong()
                        p.seekTo((dragStartPos + delta).coerceIn(0L, dur))
                        onUserInteraction()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelPending()
                    if (speedActive) restoreSpeed()
                    dragSeeking = false
                    zone = -1
                }
            }
            detector.onTouchEvent(event)
            true
        }
    }

    private fun startSpeed() {
        pendingSpeed = null
        val p = playerProvider() ?: return
        speedActive = true
        p.playbackParameters = p.playbackParameters.withSpeed(speedSteps[0])
        onUserInteraction()
    }

    private fun restoreSpeed() {
        val p = playerProvider() ?: return
        speedActive = false
        p.playbackParameters = p.playbackParameters.withSpeed(1f)
    }

    private fun cancelPending() {
        pendingSpeed?.let { root.removeCallbacks(it) }
        pendingSpeed = null
    }

    private fun seekBy(p: ExoPlayer, ms: Long) {
        val dur = p.duration.coerceAtLeast(0L)
        val target = (p.currentPosition + ms).coerceIn(0L, dur)
        p.seekTo(target)
    }
}