#include "preprocessor.h"
#include <opencv2/imgproc.hpp>
#include <android/log.h>

#define LOG_TAG "PreProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace hermivision {

void PreProcessor::yuvToRgb(
    const uint8_t* yData, const uint8_t* uvData,
    int width, int height,
    int yStride, int uvStride,
    cv::Mat& outputRgb
) {
    // Wrap raw pointers as cv::Mat headers — ZERO pixel copy
    cv::Mat yMat(height, width, CV_8UC1, const_cast<uint8_t*>(yData), yStride);
    cv::Mat uvMat(height / 2, width / 2, CV_8UC2, const_cast<uint8_t*>(uvData), uvStride);

    // Convert YUV NV21 → RGB using OpenCV (NEON-optimized on ARM64)
    cv::cvtColorTwoPlane(yMat, uvMat, outputRgb, cv::COLOR_YUV2RGB_NV21);
}

void PreProcessor::rotateFrame(cv::Mat& frame, int rotationDegrees) {
    switch (rotationDegrees) {
        case 90:
            cv::rotate(frame, frame, cv::ROTATE_90_CLOCKWISE);
            break;
        case 180:
            cv::rotate(frame, frame, cv::ROTATE_180);
            break;
        case 270:
            cv::rotate(frame, frame, cv::ROTATE_90_COUNTERCLOCKWISE);
            break;
        default:
            // 0 degrees — no rotation needed
            break;
    }
}

} // namespace hermivision
