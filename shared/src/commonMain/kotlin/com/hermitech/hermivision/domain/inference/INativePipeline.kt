package com.hermitech.hermivision.domain.inference

/**
 * SOLID (Dependency Inversion): Abstract interface for the native AI pipeline.
 *
 * KMP adaptation: ByteBuffer replaced with ByteArray for cross-platform compatibility.
 * Android actual implementation wraps ByteArray back to direct ByteBuffer before JNI call.
 * iOS actual implementation will use Core ML / TFLite iOS.
 */
interface INativePipeline {

    /**
     * Initialize the pipeline.
     * @return true if initialization succeeded
     */
    fun init(
        delegateType: Int,
        numThreads: Int,
        ballModelPath: String,
        courtModelPath: String = "",
        courtDelegateType: Int = 2
    ): Boolean

    /** Submit YUV frame to pipeline (zero-copy where possible). */
    fun submitYuvFrame(
        yData: ByteArray,
        uvData: ByteArray,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        frameId: Int
    )

    /** Submit RGB frame via native pointer (Android: OpenCV Mat; iOS: pixel buffer addr). */
    fun submitFrame(nativeAddr: Long, frameId: Int, origWidth: Int, origHeight: Int)

    /**
     * Process the latest frame through all AI models.
     * @return FloatArray[35]: [ballVisible, ballX, ballY, ballW, ballH, ballScore,
     *                          courtValid, kp1x, kp1y, ..., kp14x, kp14y]
     */
    fun processLatestFrame(): FloatArray

    /**
     * Extract 14 court keypoints from a result array.
     * @return FloatArray[28] (x,y pairs) or null if court not valid
     */
    fun getCourtKeypoints(result: FloatArray): FloatArray?

    /** Get the hardware delegate that was actually applied. */
    fun getActiveDelegate(): String

    /** Release all native resources. */
    fun release()
}
