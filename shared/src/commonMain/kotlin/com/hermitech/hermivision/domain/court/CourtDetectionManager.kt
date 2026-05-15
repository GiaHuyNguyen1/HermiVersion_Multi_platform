package com.hermitech.hermivision.domain.court

import com.hermitech.hermivision.domain.model.CourtResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CourtDetectionManager {

    companion object {
        const val COURT_REDETECT_INTERVAL_MS = 5_000L
    }

    data class MiniMapState(
        val homography: FloatArray? = null,
        val ballPosition: Pair<Float, Float>? = null,
        val isValid: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MiniMapState) return false
            return isValid == other.isValid &&
                    ballPosition == other.ballPosition &&
                    homography.contentEquals(other.homography)
        }
        override fun hashCode(): Int {
            var result = homography?.contentHashCode() ?: 0
            result = 31 * result + (ballPosition?.hashCode() ?: 0)
            result = 31 * result + isValid.hashCode()
            return result
        }
    }

    private val _courtResult = MutableStateFlow(CourtResult.EMPTY)
    val courtResult: StateFlow<CourtResult> = _courtResult.asStateFlow()

    private val _miniMapState = MutableStateFlow(MiniMapState())
    val miniMapState: StateFlow<MiniMapState> = _miniMapState.asStateFlow()

    var lastValidCourt: CourtResult = CourtResult.EMPTY
        private set

    var totalCourtDetections: Int = 0
        private set

    private var cachedHomography: FloatArray? = null

    private var lastCourtDetectionTimeMs: Long = 0L
    private var lastRejectionLogTimeMs: Long = 0L

    fun shouldRunCourtDetection(currentTimeMs: Long): Boolean {
        if (lastCourtDetectionTimeMs == 0L) return true
        return (currentTimeMs - lastCourtDetectionTimeMs) >= COURT_REDETECT_INTERVAL_MS
    }

    fun processCourtFromResult(
        result: FloatArray,
        courtValidIndex: Int,
        courtKpStartIndex: Int,
        currentTimeMs: Long,
        frameWidth: Float,
        frameHeight: Float
    ) {
        val courtValid = result[courtValidIndex] > 0.5f
        if (!courtValid) return

        val keypoints = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until 14) {
            val x = result[courtKpStartIndex + i * 2]
            val y = result[courtKpStartIndex + i * 2 + 1]
            keypoints.add(Pair(x, y))
        }

        val validation = CourtValidator.validate(keypoints, frameWidth, frameHeight)
        if (!validation.isValid) {
            // Log rejection with keypoint dump (throttled to once per 5s)
            if (currentTimeMs - lastRejectionLogTimeMs >= COURT_REDETECT_INTERVAL_MS) {
                lastRejectionLogTimeMs = currentTimeMs
                println("[HermiVision][CourtManager] Court REJECTED: ${validation.reason}")
                println(CourtValidator.dumpKeypoints(keypoints, frameWidth, frameHeight))
            }
            return
        }

        val court = CourtResult(valid = true, keypoints = keypoints)
        _courtResult.value = court
        lastValidCourt = court

        val cameraCorners = listOf(keypoints[0], keypoints[1], keypoints[2], keypoints[3])
        cachedHomography = CourtHomography.computeCourtToMiniMap(cameraCorners)

        val isNewDetection = lastCourtDetectionTimeMs == 0L ||
                (currentTimeMs - lastCourtDetectionTimeMs) >= COURT_REDETECT_INTERVAL_MS
        if (isNewDetection) {
            val elapsedMs = if (lastCourtDetectionTimeMs > 0) currentTimeMs - lastCourtDetectionTimeMs else 0L
            lastCourtDetectionTimeMs = currentTimeMs
            totalCourtDetections++
            println("[HermiVision][CourtManager] Court ACCEPTED #$totalCourtDetections (elapsed=${elapsedMs}ms)")
            println(CourtValidator.dumpKeypoints(keypoints, frameWidth, frameHeight))
        }
    }

    fun processCourtKeypoints(
        valid: Boolean,
        keypoints: List<Pair<Float, Float>>,
        currentTimeMs: Long,
        frameWidth: Float,
        frameHeight: Float
    ) {
        if (!valid || keypoints.size != 14) return

        val validation = CourtValidator.validate(keypoints, frameWidth, frameHeight)
        if (!validation.isValid) {
            println("[HermiVision][CourtManager] Court REJECTED: ${validation.reason}")
            return
        }

        val court = CourtResult(valid = true, keypoints = keypoints)
        _courtResult.value = court
        lastValidCourt = court

        val isNewDetection = lastCourtDetectionTimeMs == 0L ||
                (currentTimeMs - lastCourtDetectionTimeMs) >= COURT_REDETECT_INTERVAL_MS
        if (isNewDetection) {
            val elapsedMs = if (lastCourtDetectionTimeMs > 0) currentTimeMs - lastCourtDetectionTimeMs else 0L
            lastCourtDetectionTimeMs = currentTimeMs
            totalCourtDetections++
            println("[HermiVision][CourtManager] Court ACCEPTED #$totalCourtDetections (elapsed=${elapsedMs}ms)")
        }

        val cameraCorners = listOf(keypoints[0], keypoints[1], keypoints[2], keypoints[3])
        cachedHomography = CourtHomography.computeCourtToMiniMap(cameraCorners)
    }

    fun updateMiniMap(ballVisible: Boolean, ballX: Float, ballY: Float) {
        val H = cachedHomography
        var miniMapBall: Pair<Float, Float>? = null

        if (ballVisible && H != null) {
            miniMapBall = CourtHomography.transformPoint(H, ballX, ballY)
            miniMapBall?.let { (mx, my) ->
                if (mx < 0 || my < 0 ||
                    mx > CourtHomography.MINI_MAP_WIDTH ||
                    my > CourtHomography.MINI_MAP_HEIGHT) {
                    miniMapBall = null
                }
            }
        }

        _miniMapState.value = MiniMapState(
            homography = cachedHomography,
            ballPosition = miniMapBall,
            isValid = cachedHomography != null
        )
    }

    fun reset() {
        _courtResult.value = CourtResult.EMPTY
        _miniMapState.value = MiniMapState()
        lastValidCourt = CourtResult.EMPTY
        totalCourtDetections = 0
        cachedHomography = null
        lastCourtDetectionTimeMs = 0L
    }
}