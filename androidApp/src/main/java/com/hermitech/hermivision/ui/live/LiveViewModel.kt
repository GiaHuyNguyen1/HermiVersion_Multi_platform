package com.hermitech.hermivision.ui.live

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.hermitech.hermivision.data.AppDatabaseFactory
import com.hermitech.hermivision.data.DatabaseDriverFactory
import com.hermitech.hermivision.data.toAIConfig
import com.hermitech.hermivision.domain.court.CourtHomography
import com.hermitech.hermivision.data.inference.NativePipeline
import com.hermitech.hermivision.domain.inference.DelegateType
import com.hermitech.hermivision.domain.model.CourtResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * ViewModel for live camera analysis.
 *
 * Manages:
 *  - Pipeline lifecycle (init → release)
 *  - Real-time result parsing from float[35] → UI state
 *  - FPS calculation (rolling average)
 *  - Session stats (total frames, ball detections)
 *  - Court homography (perspective → 2D mini-map)
 *  - Stop → results transition
 */
class LiveViewModel : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"
        private const val COURT_MODEL_NAME = "Court-Keypoint-FP32.tflite"
        private const val FPS_WINDOW_SIZE = 30
    }

    // ── Pipeline ──
    val pipeline = NativePipeline()

    // ── UI State ──
    data class BallState(
        val isVisible: Boolean = false,
        val x: Float = 0f,
        val y: Float = 0f,
        val w: Float = 0f,
        val h: Float = 0f,
        val score: Float = 0f
    )

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

    data class SessionStats(
        val totalFrames: Int = 0,
        val ballDetections: Int = 0,
        val courtDetections: Int = 0,
        val avgFps: Float = 0f,
        val durationMs: Long = 0L,
        val lastCourtResult: CourtResult = CourtResult.EMPTY
    )

    private val _ballState = MutableStateFlow(BallState())
    val ballState: StateFlow<BallState> = _ballState.asStateFlow()

    private val _courtResult = MutableStateFlow(CourtResult.EMPTY)
    val courtResult: StateFlow<CourtResult> = _courtResult.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    private val _isStopped = MutableStateFlow(false)
    val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _miniMapState = MutableStateFlow(MiniMapState())
    val miniMapState: StateFlow<MiniMapState> = _miniMapState.asStateFlow()

    // ── Session tracking ──
    private var sessionStartTimeMs = 0L
    private var totalFramesProcessed = 0
    private var totalBallDetections = 0
    private var totalCourtDetections = 0
    private var lastValidCourt = CourtResult.EMPTY

    // ── Homography cache ──
    private var cachedHomography: FloatArray? = null

    // ── FPS tracking ──
    private val frameTimes = LongArray(FPS_WINDOW_SIZE)
    private var frameTimeIndex = 0
    private var frameCount = 0
    private var lastFrameTimeNs = 0L
    private var fpsSum = 0f
    private var fpsCount = 0

    /**
     * Initialize the C++ pipeline (model extraction + JNI init).
     */
    suspend fun initPipeline(context: Context) {
        if (_isInitialized.value) return

        try {
            if (!OpenCVLoader.initLocal()) {
                _initError.value = "OpenCV initialization failed"
                return
            }

            val db = AppDatabaseFactory.create(DatabaseDriverFactory().createDriver())
            val row = db.deviceConfigQueries.getConfig().executeAsOneOrNull()
            if (row == null) {
                _initError.value = "Device not optimized. Please restart the app."
                return
            }
            val config = row.toAIConfig()
            Log.i(TAG, "AI Config: ${config.deviceSummary}")

            val ballModelPath = com.hermitech.hermivision.data.inference.ModelCache.extractModelToCache(context, config.yoloModelName)
            val courtModelPath = com.hermitech.hermivision.data.inference.ModelCache.extractModelToCache(context, COURT_MODEL_NAME)

            val ok = pipeline.init(
                delegateType = config.tfliteDelegate.ordinal,
                numThreads = config.numThreads,
                ballModelPath = ballModelPath,
                courtModelPath = courtModelPath,
                courtDelegateType = DelegateType.CPU.ordinal
            )

            if (!ok) {
                _initError.value = "C++ pipeline initialization failed"
                return
            }

            Log.i(TAG, "Pipeline initialized — delegate: ${pipeline.getActiveDelegate()}")
            sessionStartTimeMs = System.currentTimeMillis()
            _isInitialized.value = true

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline init failed", e)
            _initError.value = "Pipeline init error: ${e.message}"
        }
    }

    /**
     * Called from CameraAnalyzer for each processed frame.
     */
    fun onResult(result: FloatArray) {
        if (_isStopped.value) return

        // ── Parse ball (indices 0-5) ──
        val ballVisible = result[NativePipeline.IDX_BALL_VISIBLE] > 0.5f
        _ballState.value = BallState(
            isVisible = ballVisible,
            x = result[NativePipeline.IDX_BALL_X],
            y = result[NativePipeline.IDX_BALL_Y],
            w = result[NativePipeline.IDX_BALL_W],
            h = result[NativePipeline.IDX_BALL_H],
            score = result[NativePipeline.IDX_BALL_SCORE]
        )

        // ── Parse court (indices 6-34) ──
        val courtValid = result[NativePipeline.IDX_COURT_VALID] > 0.5f
        if (courtValid) {
            val keypoints = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until 14) {
                val x = result[NativePipeline.IDX_COURT_KP_START + i * 2]
                val y = result[NativePipeline.IDX_COURT_KP_START + i * 2 + 1]
                keypoints.add(Pair(x, y))
            }
            val court = CourtResult(valid = true, keypoints = keypoints)
            _courtResult.value = court
            lastValidCourt = court
            totalCourtDetections++

            // ── Recompute homography from 4 outer corners ──
            val cameraCorners = listOf(
                keypoints[0],  // top-left
                keypoints[1],  // top-right
                keypoints[2],  // bottom-left
                keypoints[3]   // bottom-right
            )
            cachedHomography = CourtHomography.computeCourtToMiniMap(cameraCorners)
        }

        // ── Project ball onto mini-map ──
        val H = cachedHomography
        var miniMapBall: Pair<Float, Float>? = null
        if (ballVisible && H != null) {
            val ballX = result[NativePipeline.IDX_BALL_X]
            val ballY = result[NativePipeline.IDX_BALL_Y]
            miniMapBall = CourtHomography.transformPoint(H, ballX, ballY)
            // Clamp to mini-map bounds
            miniMapBall?.let { (mx, my) ->
                if (mx < 0 || my < 0 ||
                    mx > CourtHomography.MINI_MAP_WIDTH ||
                    my > CourtHomography.MINI_MAP_HEIGHT) {
                    miniMapBall = null  // Ball outside court area
                }
            }
        }

        _miniMapState.value = MiniMapState(
            homography = cachedHomography,
            ballPosition = miniMapBall,
            isValid = cachedHomography != null
        )

        // ── Track session ──
        totalFramesProcessed++
        if (ballVisible) totalBallDetections++

        updateFps()
    }

    /**
     * Stop analysis and compute final session stats.
     */
    fun stopSession() {
        _isStopped.value = true
        val durationMs = System.currentTimeMillis() - sessionStartTimeMs
        val avgFps = if (fpsCount > 0) fpsSum / fpsCount else 0f

        _sessionStats.value = SessionStats(
            totalFrames = totalFramesProcessed,
            ballDetections = totalBallDetections,
            courtDetections = totalCourtDetections,
            avgFps = avgFps,
            durationMs = durationMs,
            lastCourtResult = lastValidCourt
        )
        Log.i(TAG, "Session stopped — $totalFramesProcessed frames, $totalBallDetections ball hits, ${durationMs}ms")
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastFrameTimeNs > 0) {
            val deltaMs = (now - lastFrameTimeNs) / 1_000_000f
            frameTimes[frameTimeIndex % FPS_WINDOW_SIZE] = deltaMs.toLong()
            frameTimeIndex++
            frameCount++

            if (frameCount >= FPS_WINDOW_SIZE) {
                val count = minOf(frameCount, FPS_WINDOW_SIZE)
                val avgMs = frameTimes.take(count).average()
                if (avgMs > 0) {
                    val currentFps = (1000.0 / avgMs).toFloat()
                    _fps.value = currentFps
                    fpsSum += currentFps
                    fpsCount++
                }
            }
        }
        lastFrameTimeNs = now
    }


    override fun onCleared() {
        super.onCleared()
        pipeline.release()
        Log.i(TAG, "Pipeline released on ViewModel cleared")
    }
}
