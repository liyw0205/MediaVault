package com.mediavault.scrape

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScrapePhase { IDLE, RUNNING, DONE, ERROR, CANCELLED }

data class ScrapeUiState(
    val phase: ScrapePhase = ScrapePhase.IDLE,
    val rebuild: Boolean = false,
    val message: String = "",
    val batchCount: Int = 0,
    val totalInLibrary: Int = 0,
    val currentFileLabel: String = "",
    val lastBatchAt: String = "",
    val canResume: Boolean = false,
    /** 本轮刮削完成时库内 TMDB「仅热度」匹配条数（T1-c） */
    val weakTmdbCount: Int = 0,
    /** 当前阶段队列总量（本地/远程待处理视频数，T3） */
    val queueTotal: Int = 0,
    /** 当前阶段已完成数（相对 queueTotal） */
    val queueDone: Int = 0,
    /** 当前阶段标签：本地 / 远程 */
    val scopeLabel: String = "",
)