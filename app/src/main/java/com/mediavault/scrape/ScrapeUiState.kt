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
    val lastBatchAt: String = "",
    val canResume: Boolean = false,
)