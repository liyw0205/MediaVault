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
)