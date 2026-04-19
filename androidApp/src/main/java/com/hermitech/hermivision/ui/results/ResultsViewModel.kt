package com.hermitech.hermivision.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermitech.hermivision.data.ProcessingResult
import com.hermitech.hermivision.data.ProcessingResultRepository
import com.hermitech.hermivision.domain.model.BallFrame
import com.hermitech.hermivision.domain.model.CourtResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ResultsUiState(
    val ballFrames: List<BallFrame> = emptyList(),
    val totalFrames: Int = 0,
    val visibleFrames: Int = 0,
    val inferenceTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val courtResult: CourtResult = CourtResult.EMPTY,
    val detectionRate: Float = 0f,
    val effectiveFps: Double = 0.0,
    val isEmpty: Boolean = true
)

private fun ProcessingResult.toUiState() = ResultsUiState(
    ballFrames = ballFrames,
    totalFrames = totalFrames,
    visibleFrames = visibleFrames,
    inferenceTimeMs = inferenceTimeMs,
    durationMs = durationMs,
    courtResult = courtResult,
    detectionRate = detectionRate,
    effectiveFps = effectiveFps,
    isEmpty = false
)

/**
 * ViewModel for [ResultsScreen].
 *
 * Observes [ProcessingResultRepository] (StateFlow) and exposes [ResultsUiState].
 * Replaces direct access to [VideoProcessingWorker.ResultHolder].
 */
class ResultsViewModel : ViewModel() {

    val uiState: StateFlow<ResultsUiState> = ProcessingResultRepository.result
        .map { result -> result?.toUiState() ?: ResultsUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ResultsUiState()
        )

    fun clearResults() {
        ProcessingResultRepository.clear()
    }
}
