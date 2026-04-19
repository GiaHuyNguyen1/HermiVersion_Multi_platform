#pragma once

#include <opencv2/core.hpp>
#include <cstdint>

namespace hermivision {

/**
 * Image pre-processing utilities.
 *
 * Handles YUV→RGB conversion and frame rotation — operations that MUST
 * happen in C++ (not Kotlin) to avoid GC pressure and JNI copy overhead.
 */
class PreProcessor {
public:
    /**
     * Convert NV21/NV12 YUV planes to RGB cv::Mat.
     *
     * This is the zero-copy path for CameraX frames:
     *   Kotlin passes raw ByteBuffer pointers → C++ wraps as cv::Mat headers
     *   (no pixel copy until cvtColor)
     *
     * @param yData     Y plane pointer
     * @param uvData    Interleaved UV plane pointer (NV21 format)
     * @param width     Frame width
     * @param height    Frame height
     * @param yStride   Row stride of Y plane
     * @param uvStride  Row stride of UV plane
     * @param outputRgb Output RGB Mat (pre-allocated or will be allocated)
     */
    static void yuvToRgb(
        const uint8_t* yData, const uint8_t* uvData,
        int width, int height,
        int yStride, int uvStride,
        cv::Mat& outputRgb
    );

    /**
     * Rotate frame by degrees (0, 90, 180, 270).
     * Used to correct camera sensor orientation.
     * Do this in C++ — NOT in Kotlin (OpenCV C++ uses NEON SIMD).
     */
    static void rotateFrame(cv::Mat& frame, int rotationDegrees);
};

} // namespace hermivision
