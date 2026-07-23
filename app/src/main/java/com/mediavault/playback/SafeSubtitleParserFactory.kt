package com.mediavault.playback

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3 默认 SSA/ASS 解析对部分 MKV 内嵌轨会直接抛 IllegalStateException，
 * 导致整条播放 Source/runtime error。这里吞掉解析失败，输出空 cues，音视频继续播。
 */
@UnstableApi
object SafeSubtitleParserFactory : SubtitleParser.Factory {
    private const val TAG = "SafeSubtitleParser"
    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val inner = delegate.create(format)
        return object : SubtitleParser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                try {
                    inner.parse(data, offset, length, outputOptions, output)
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "subtitle parse failed mime=${format.sampleMimeType} lang=${format.language}: ${t.message}",
                    )
                    // 不向下游输出，等同跳过坏样本，避免中断播放
                }
            }

            override fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle {
                return try {
                    inner.parseToLegacySubtitle(data, offset, length)
                } catch (t: Throwable) {
                    Log.w(TAG, "legacy subtitle parse failed: ${t.message}")
                    EmptySubtitle
                }
            }

            override fun reset() {
                try {
                    inner.reset()
                } catch (_: Throwable) {
                }
            }

            override fun getCueReplacementBehavior(): Int = inner.cueReplacementBehavior
        }
    }

    private object EmptySubtitle : Subtitle {
        override fun getEventTimeCount(): Int = 0
        override fun getEventTime(index: Int): Long = 0L
        override fun getNextEventTimeIndex(timeUs: Long): Int = -1
        override fun getCues(timeUs: Long): List<androidx.media3.common.text.Cue> = emptyList()
    }
}
