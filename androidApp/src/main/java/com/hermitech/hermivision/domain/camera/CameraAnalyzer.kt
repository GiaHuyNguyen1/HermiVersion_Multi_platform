package com.hermitech.hermivision.domain.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hermitech.hermivision.data.inference.NativePipeline

/**
 * CameraX ImageAnalysis.Analyzer that feeds camera frames into the C++ pipeline.
 *
 * Uses the SAME native pipeline API as video decoding (submitYuvFrame → processLatestFrame).
 * CameraX provides YUV_420_888 frames which map directly to our existing JNI path.
 *
 * Backpressure: CameraX KEEP_ONLY_LATEST strategy drops old frames when model is busy.
 *
 * CRITICAL: imageProxy.close() is called in `finally` block to prevent camera stall.
 */
class CameraAnalyzer(private val pipeline: NativePipeline, private val onResult: (FloatArray) -> Unit) : ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "CameraAnalyzer"
    }

    private var frameId = 0

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val yPlane = imageProxy.planes[0]
            val uvPlane = imageProxy.planes[1]

            pipeline.submitYuvFrameDirect(
                yBuffer = yPlane.buffer,
                uvBuffer = uvPlane.buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                yStride = yPlane.rowStride,
                uvStride = uvPlane.rowStride,
                frameId = frameId
            )

            val result = pipeline.processLatestFrame()
            onResult(result)
            frameId++
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame $frameId", e)
        } finally {
            imageProxy.close()  //must always close to unblock camera pipeline
        }
    }
}
