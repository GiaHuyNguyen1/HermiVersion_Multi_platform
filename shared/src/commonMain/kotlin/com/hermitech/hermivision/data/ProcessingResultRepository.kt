package com.hermitech.hermivision.data

import com.hermitech.hermivision.domain.model.BallFrame
import com.hermitech.hermivision.domain.model.CourtResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory result of a completed video processing session.
 *
 * Produced by the background processing task, consumed by ResultsViewModel.
 */
data class ProcessingResult(
    val ballFrames: List<BallFrame>,
    val totalFrames: Int,
    val visibleFrames: Int,
    val avgConfidence: Float,
    val inferenceTimeMs: Long,
    val durationMs: Long,
    val courtResult: CourtResult
) {
    companion object {
        val EMPTY = ProcessingResult(
            ballFrames = emptyList(),
            totalFrames = 0,
            visibleFrames = 0,
            avgConfidence = 0f,
            inferenceTimeMs = 0L,
            durationMs = 0L,
            courtResult = CourtResult.EMPTY
        )
    }

    /** Detection rate [0.0, 1.0] */
    val detectionRate: Float
        get() = if (totalFrames > 0) visibleFrames.toFloat() / totalFrames else 0f

    /** Effective processing FPS */
    val effectiveFps: Double
        get() = if (inferenceTimeMs > 0) totalFrames * 1000.0 / inferenceTimeMs else 0.0
}

/**
 * Repository (singleton) that bridges background processing → UI ViewModels.
 *
 * KMP version: pure commonMain coroutines StateFlow, no Android-specific dependencies.
 */
object ProcessingResultRepository {

    private val _result = MutableStateFlow<ProcessingResult?>(null)

    /** Observe the latest processing result. Null = no result yet / cleared. */
    val result: StateFlow<ProcessingResult?> = _result.asStateFlow()

    /** Called by the processing task when processing completes. */
    fun publish(result: ProcessingResult) {
        _result.value = result
    }

    /** Clear results (e.g. when user picks a new video). */
    fun clear() {
        _result.value = null
    }
}
