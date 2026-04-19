#include "court_detector.h"

#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <cstring>
#include <chrono>

#define LOG_TAG "CourtDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

// Static constexpr member definitions (required for ODR-use in C++17)
constexpr float CourtDetector::MEAN[3];
constexpr float CourtDetector::STD[3];

// ════════════════════════════════════════════════════════════════════════════
// Model Lifecycle
// ════════════════════════════════════════════════════════════════════════════

bool CourtDetector::loadModel(const std::string& modelPath, DelegateType delegate, int numThreads) {
    // 1. Load model from file
    model_ = TfLiteModelCreateFromFile(modelPath.c_str());
    if (!model_) {
        LOGE("Failed to load court model: %s", modelPath.c_str());
        return false;
    }

    // 2. Extract cache directory from model path (for GPU shader serialization)
    std::string cacheDir = "";
    size_t lastSlash = modelPath.find_last_of('/');
    if (lastSlash != std::string::npos) {
        cacheDir = modelPath.substr(0, lastSlash);
    }

    // 3. Create interpreter with delegate auto-fallback
    delegateManager_ = std::make_unique<DelegateManager>();
    TfLiteInterpreter* interp = delegateManager_->createInterpreter(model_, cacheDir, delegate, numThreads);
    if (!interp) {
        LOGE("Failed to create interpreter for court model: %s", modelPath.c_str());
        TfLiteModelDelete(model_);
        model_ = nullptr;
        return false;
    }

    // 4. Validate tensor shapes
    const TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(interp, 0);
    if (TfLiteTensorNumDims(inputTensor) != 4) {
        LOGE("Unexpected court input tensor dims: %d (expected 4)", TfLiteTensorNumDims(inputTensor));
        release();
        return false;
    }

    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(interp, 0);
    int outputSize = TfLiteTensorDim(outputTensor, 1);
    if (outputSize != NUM_OUTPUTS) {
        LOGW("Court output size: %d (expected %d)", outputSize, NUM_OUTPUTS);
    }

    // 5. Pre-allocate reusable cv::Mat buffers (ONE-TIME allocation)
    resizedMat_ = cv::Mat(INPUT_SIZE, INPUT_SIZE, CV_8UC3);
    floatMat_   = cv::Mat(INPUT_SIZE, INPUT_SIZE, CV_32FC3);

    LOGI("Court model loaded: %s on %s", modelPath.c_str(),
         delegateManager_->getActiveDelegateName().c_str());
    LOGI("  Input:  [1, %d, %d, 3] float32",
         TfLiteTensorDim(inputTensor, 1), TfLiteTensorDim(inputTensor, 2));
    LOGI("  Output: [1, %d] float32", outputSize);

    return true;
}

void CourtDetector::release() {
    if (delegateManager_) delegateManager_->release();   // Releases interpreter + delegate

    if (model_) {
        TfLiteModelDelete(model_);
        model_ = nullptr;
    }

    resizedMat_.release();
    floatMat_.release();

    LOGI("Court model released");
}

std::string CourtDetector::getActiveDelegate() const {
    return delegateManager_ ? delegateManager_->getActiveDelegateName() : "none";
}

// ════════════════════════════════════════════════════════════════════════════
// Main Process — called every N frames from background court thread
// ════════════════════════════════════════════════════════════════════════════

void CourtDetector::process(FrameContext& ctx, FramePool& pool) {
    TfLiteInterpreter* interp = delegateManager_ ? delegateManager_->getInterpreter() : nullptr;
    if (!interp) {
        ctx.courtKeypoints.valid = false;
        return;
    }

    // 1. Acquire frame from pool (refCount++)
    FrameSlot* slot = pool.acquireLatest();
    if (!slot) {
        ctx.courtKeypoints.valid = false;
        return;
    }

    // 2. Use frame data from pool slot
    ctx.originalWidth = slot->originalWidth;
    ctx.originalHeight = slot->originalHeight;
    ctx.frameId = slot->frameId;

    // ── Timing instrumentation ──
    auto t0 = std::chrono::high_resolution_clock::now();

    // 3. Pre-process: Simple resize + ImageNet normalize → copy to TFLite input tensor
    preprocess(slot->rgb, slot->originalWidth, slot->originalHeight);

    // 4. Release frame (refCount--) — data already copied to TFLite input tensor
    slot->release();

    auto t1 = std::chrono::high_resolution_clock::now();

    // 5. Invoke inference
    if (TfLiteInterpreterInvoke(interp) != kTfLiteOk) {
        LOGW("Court TFLite invoke failed on frame %d", ctx.frameId);
        ctx.courtKeypoints.valid = false;
        return;
    }

    auto t2 = std::chrono::high_resolution_clock::now();

    // 6. Post-process: Scale [0,1] keypoints → original pixel coordinates
    postprocess(ctx);

    auto t3 = std::chrono::high_resolution_clock::now();

    // Log timing (court runs infrequently, so log every time)
    auto preMs  = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    auto invMs  = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    auto postMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t2).count();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t0).count();
    LOGI("Court frame %d timing: pre=%lldms invoke=%lldms post=%lldms total=%lldms",
         ctx.frameId, (long long)preMs, (long long)invMs, (long long)postMs, (long long)totalMs);
}

// ════════════════════════════════════════════════════════════════════════════
// Pre-processing: Simple Resize + ImageNet Normalize
//
// Key differences from YOLO:
//   - NO letterbox — stretch to 480×480 (model trained with stretch)
//   - ImageNet-style normalize: (pixel/255 - mean) / std
// ════════════════════════════════════════════════════════════════════════════

void CourtDetector::preprocess(const cv::Mat& rgbFrame, int origW, int origH) {
    // 1. Simple resize (stretch) — NO letterbox, NO padding
    cv::resize(rgbFrame, resizedMat_, cv::Size(INPUT_SIZE, INPUT_SIZE), 0, 0, cv::INTER_LINEAR);

    // 2. Convert uint8 → float32 + ImageNet normalize
    //    Formula per pixel per channel: (value/255.0 - mean[c]) / std[c]
    const int totalPixels = INPUT_SIZE * INPUT_SIZE;
    const uint8_t* src = resizedMat_.data;
    float* dst = reinterpret_cast<float*>(floatMat_.data);

    for (int i = 0; i < totalPixels; ++i) {
        // RGB layout: src[i*3+0]=R, src[i*3+1]=G, src[i*3+2]=B
        dst[i * 3 + 0] = (static_cast<float>(src[i * 3 + 0]) / 255.0f - MEAN[0]) / STD[0];
        dst[i * 3 + 1] = (static_cast<float>(src[i * 3 + 1]) / 255.0f - MEAN[1]) / STD[1];
        dst[i * 3 + 2] = (static_cast<float>(src[i * 3 + 2]) / 255.0f - MEAN[2]) / STD[2];
    }

    // 3. Copy to TFLite input tensor (NHWC layout matches)
    TfLiteInterpreter* interp = delegateManager_->getInterpreter();
    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(interp, 0);

    TfLiteTensorCopyFromBuffer(
        inputTensor,
        floatMat_.data,
        INPUT_SIZE * INPUT_SIZE * 3 * sizeof(float)
    );
}

// ════════════════════════════════════════════════════════════════════════════
// Post-processing: Scale [0,1] sigmoid outputs → original pixel coords
// ════════════════════════════════════════════════════════════════════════════

void CourtDetector::postprocess(FrameContext& ctx) {
    TfLiteInterpreter* interp = delegateManager_->getInterpreter();
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(interp, 0);

    // Output layout: [1, 28] — 14 keypoints × 2 (x, y), normalized [0,1] via sigmoid
    const float* output = reinterpret_cast<const float*>(TfLiteTensorData(outputTensor));

    ctx.courtKeypoints.valid = true;
    for (int i = 0; i < NUM_KEYPOINTS; ++i) {
        // Scale normalized [0,1] → original pixel coordinates
        ctx.courtKeypoints.points[i * 2]     = output[i * 2]     * static_cast<float>(ctx.originalWidth);
        ctx.courtKeypoints.points[i * 2 + 1] = output[i * 2 + 1] * static_cast<float>(ctx.originalHeight);
    }
}

} // namespace hermivision
