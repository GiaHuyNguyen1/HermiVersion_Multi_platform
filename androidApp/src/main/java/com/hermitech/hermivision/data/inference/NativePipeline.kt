package com.hermitech.hermivision.data.inference

import com.hermitech.hermivision.domain.inference.INativePipeline
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android JNI implementation of INativePipeline.
 *
 * KMP adaptation:
 *   - Public interface uses ByteArray (KMP-compatible)
 *   - Internally wraps ByteArray to direct ByteBuffer before JNI calls
 *     to preserve zero-copy performance for the YUV path.
 *
 * All heavy computation happens in native C++:
 *   - YUV→RGB conversion (NEON SIMD)
 *   - Pre-processing (Letterbox, Normalize)
 *   - TFLite inference (with NNAPI/GPU/CPU delegate)
 *   - Post-processing (NMS, un-letterbox)
 */
class NativePipeline : INativePipeline {

    companion object {
        private const val TAG = "NativePipeline"

        // Result array layout: float[35]
        const val IDX_BALL_VISIBLE  = 0
        const val IDX_BALL_X        = 1
        const val IDX_BALL_Y        = 2
        const val IDX_BALL_W        = 3
        const val IDX_BALL_H        = 4
        const val IDX_BALL_SCORE    = 5
        const val IDX_COURT_VALID   = 6
        const val IDX_COURT_KP_START = 7  // 28 floats: kp1x, kp1y, ..., kp14x, kp14y

        const val RESULT_SIZE = 35  // 6 (ball) + 1 (courtValid) + 28 (keypoints)

        init {
            System.loadLibrary("hermivision_native")
            Log.i(TAG, "hermivision_native library loaded")
        }
    }

    private var initialized = false

    override fun init(
        delegateType: Int,
        numThreads: Int,
        ballModelPath: String,
        courtModelPath: String,
        courtDelegateType: Int
    ): Boolean {
        val ok = nativeInitPipeline(delegateType, numThreads, ballModelPath, courtModelPath, courtDelegateType)
        initialized = ok
        if (ok) Log.i(TAG, "Pipeline initialized: delegate=${getActiveDelegate()}")
        else     Log.e(TAG, "Pipeline initialization FAILED")
        return ok
    }

    /**
     * KMP interface: accepts ByteArray.
     * Wraps to direct ByteBuffer for JNI performance.
     */
    override fun submitYuvFrame(
        yData: ByteArray, uvData: ByteArray,
        width: Int, height: Int,
        yStride: Int, uvStride: Int,
        frameId: Int
    ) {
        if (!initialized) return
        val yBuf  = ByteBuffer.allocateDirect(yData.size).order(ByteOrder.nativeOrder()).put(yData).also { it.rewind() }
        val uvBuf = ByteBuffer.allocateDirect(uvData.size).order(ByteOrder.nativeOrder()).put(uvData).also { it.rewind() }
        nativeSubmitYuvFrame(yBuf, uvBuf, width, height, yStride, uvStride, frameId)
    }

    /**
     * High-performance variant accepting direct ByteBuffers from CameraX/VideoDecoder.
     * Use this when you already have direct ByteBuffers to avoid an extra copy.
     */
    fun submitYuvFrameDirect(
        yBuffer: ByteBuffer, uvBuffer: ByteBuffer,
        width: Int, height: Int,
        yStride: Int, uvStride: Int,
        frameId: Int
    ) {
        if (!initialized) return
        nativeSubmitYuvFrame(yBuffer, uvBuffer, width, height, yStride, uvStride, frameId)
    }

    override fun submitFrame(nativeAddr: Long, frameId: Int, origWidth: Int, origHeight: Int) {
        if (!initialized) return
        nativeSubmitFrame(nativeAddr, frameId, origWidth, origHeight)
    }

    override fun processLatestFrame(): FloatArray {
        if (!initialized) return FloatArray(RESULT_SIZE)
        return nativeProcessLatestFrame()
    }

    /** Legacy single-frame path — prefer submitFrame + processLatestFrame. */
    fun processFrame(matAddr: Long, frameId: Int, origWidth: Int, origHeight: Int): FloatArray {
        if (!initialized) return FloatArray(RESULT_SIZE)
        return nativeProcessFrame(matAddr, frameId, origWidth, origHeight)
    }

    override fun getCourtKeypoints(result: FloatArray): FloatArray? {
        if (result.size < RESULT_SIZE || result[IDX_COURT_VALID] <= 0.5f) return null
        return result.copyOfRange(IDX_COURT_KP_START, IDX_COURT_KP_START + 28)
    }

    override fun getActiveDelegate(): String = nativeGetActiveDelegate()

    override fun release() {
        if (initialized) {
            nativeReleasePipeline()
            initialized = false
            Log.i(TAG, "Pipeline released")
        }
    }

    // ── JNI native declarations ──
    private external fun nativeInitPipeline(delegateType: Int, numThreads: Int, ballModelPath: String, courtModelPath: String, courtDelegateType: Int): Boolean
    private external fun nativeSubmitYuvFrame(yBuffer: ByteBuffer, uvBuffer: ByteBuffer, width: Int, height: Int, yStride: Int, uvStride: Int, frameId: Int)
    private external fun nativeSubmitFrame(matAddr: Long, frameId: Int, origWidth: Int, origHeight: Int)
    private external fun nativeProcessLatestFrame(): FloatArray
    private external fun nativeProcessFrame(matAddr: Long, frameId: Int, origWidth: Int, origHeight: Int): FloatArray
    private external fun nativeReleasePipeline()
    private external fun nativeGetActiveDelegate(): String
}