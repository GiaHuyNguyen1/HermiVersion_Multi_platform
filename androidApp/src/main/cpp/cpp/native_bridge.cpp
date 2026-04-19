#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>

#include "engine/hermivision_pipeline.h"
#include "engine/pipeline_builder.h"

#include <opencv2/core.hpp>

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ════════════════════════════════════════════════════════════════════════════
// Global pipeline instance
// Lives for the duration of a processing session (init → process → release)
// ════════════════════════════════════════════════════════════════════════════
static hermivision::HermiVisionPipeline* g_pipeline = nullptr;

extern "C" {

// ────────────────────────────────────────────────────────────────────────────
// initPipeline(delegateType, numThreads, ballModelPath, courtModelPath, courtDelegateType) → boolean
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeInitPipeline(
    JNIEnv* env, jobject /* this */,
    jint delegateType, jint numThreads,
    jstring ballModelPath, jstring courtModelPath,
    jint courtDelegateType
) {
    if (g_pipeline) {
        g_pipeline->release();
        delete g_pipeline;
        g_pipeline = nullptr;
    }

    const char* ballPath = env->GetStringUTFChars(ballModelPath, nullptr);
    if (!ballPath) {
        LOGE("Failed to get ball model path string");
        return JNI_FALSE;
    }

    hermivision::PipelineBuilder builder;
    builder.withBallModel(
        std::string(ballPath),
        static_cast<hermivision::DelegateType>(delegateType),
        numThreads
    );
    env->ReleaseStringUTFChars(ballModelPath, ballPath);

    if (courtModelPath != nullptr) {
        const char* courtPath = env->GetStringUTFChars(courtModelPath, nullptr);
        if (courtPath) {
            builder.withCourtModel(
                std::string(courtPath),
                static_cast<hermivision::DelegateType>(courtDelegateType)
            );
            env->ReleaseStringUTFChars(courtModelPath, courtPath);
        }
    }

    auto pipeline = builder.build();
    if (!pipeline) {
        LOGE("Pipeline initialization FAILED");
        return JNI_FALSE;
    }

    g_pipeline = pipeline.release();
    LOGI("Pipeline initialized from JNI");
    return JNI_TRUE;
}

// ────────────────────────────────────────────────────────────────────────────
// submitYuvFrame(yBuffer, uvBuffer, width, height, yStride, uvStride, frameId)
// Zero-copy YUV submission from decoder/camera → FramePool
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeSubmitYuvFrame(
    JNIEnv* env, jobject /* this */,
    jobject yBuffer, jobject uvBuffer,
    jint width, jint height,
    jint yStride, jint uvStride,
    jint frameId
) {
    if (!g_pipeline) return;

    auto* yData  = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uvData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uvBuffer));

    if (!yData || !uvData) {
        LOGE("submitYuvFrame: invalid ByteBuffer (not direct?)");
        return;
    }

    g_pipeline->submitYuvFrame(yData, uvData, width, height, yStride, uvStride, frameId);
}

// ────────────────────────────────────────────────────────────────────────────
// submitFrame(matAddr, frameId, origWidth, origHeight)
// Submit RGB frame via OpenCV Mat pointer (used by legacy Mat-based callers)
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeSubmitFrame(
    JNIEnv* env, jobject /* this */,
    jlong matAddr, jint frameId, jint origWidth, jint origHeight
) {
    if (!g_pipeline) return;

    cv::Mat& rgbMat = *reinterpret_cast<cv::Mat*>(matAddr);
    g_pipeline->submitFrame(rgbMat, frameId, origWidth, origHeight);
}

// ────────────────────────────────────────────────────────────────────────────
// processLatestFrame() → float[35]
// Process the latest frame in the FramePool through all AI models
// Returns: [ballVisible, ballX, ballY, ballW, ballH, ballScore,
//           courtValid, kp1x, kp1y, kp2x, kp2y, ..., kp14x, kp14y]
// Total: 6 (ball) + 1 (courtValid) + 28 (keypoints) = 35 floats
// ────────────────────────────────────────────────────────────────────────────
static constexpr int RESULT_SIZE = 35;

JNIEXPORT jfloatArray JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeProcessLatestFrame(
    JNIEnv* env, jobject /* this */
) {
    jfloatArray resultArray = env->NewFloatArray(RESULT_SIZE);

    if (!g_pipeline) {
        float empty[RESULT_SIZE] = {};
        env->SetFloatArrayRegion(resultArray, 0, RESULT_SIZE, empty);
        return resultArray;
    }

    auto result = g_pipeline->processLatestFrame();

    float data[RESULT_SIZE] = {};
    // Ball results (indices 0-5)
    data[0] = result.ballVisible ? 1.0f : 0.0f;
    data[1] = result.ballX;
    data[2] = result.ballY;
    data[3] = result.ballW;
    data[4] = result.ballH;
    data[5] = result.ballScore;
    // Court results (indices 6-34)
    data[6] = result.courtValid ? 1.0f : 0.0f;
    if (result.courtValid) {
        std::memcpy(&data[7], result.courtKeypoints, 28 * sizeof(float));
    }

    env->SetFloatArrayRegion(resultArray, 0, RESULT_SIZE, data);
    return resultArray;
}

// ────────────────────────────────────────────────────────────────────────────
// [LEGACY] processFrame(matAddr, frameId, origWidth, origHeight) → float[35]
// Kept for backward compat — submits to pool then processes immediately
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT jfloatArray JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeProcessFrame(
    JNIEnv* env, jobject /* this */,
    jlong matAddr, jint frameId, jint origWidth, jint origHeight
) {
    if (!g_pipeline) {
        jfloatArray resultArray = env->NewFloatArray(RESULT_SIZE);
        float empty[RESULT_SIZE] = {};
        env->SetFloatArrayRegion(resultArray, 0, RESULT_SIZE, empty);
        return resultArray;
    }

    // Submit → process (two-step for backward compat)
    cv::Mat& rgbMat = *reinterpret_cast<cv::Mat*>(matAddr);
    g_pipeline->submitFrame(rgbMat, frameId, origWidth, origHeight);

    // Now call processLatestFrame via the same JNI path
    return Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeProcessLatestFrame(env, nullptr);
}

// ────────────────────────────────────────────────────────────────────────────
// releasePipeline() — free all native resources
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeReleasePipeline(
    JNIEnv* env, jobject /* this */
) {
    if (g_pipeline) {
        g_pipeline->release();
        delete g_pipeline;
        g_pipeline = nullptr;
        LOGI("Pipeline released from JNI");
    }
}

// ────────────────────────────────────────────────────────────────────────────
// getActiveDelegate() → String
// ────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_hermitech_hermivision_domain_inference_NativePipeline_nativeGetActiveDelegate(
    JNIEnv* env, jobject /* this */
) {
    std::string delegate = g_pipeline ? g_pipeline->getActiveDelegate() : "none";
    return env->NewStringUTF(delegate.c_str());
}

}