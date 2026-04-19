#include "yolo_ball_detector.h"

#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cstring>
#include <chrono>

#define LOG_TAG "YoloBallDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

// ════════════════════════════════════════════════════════════════════════════
// Model Lifecycle
// ════════════════════════════════════════════════════════════════════════════

bool YoloBallDetector::loadModel(const std::string& modelPath, DelegateType delegate, int numThreads) {
    // 1. Load model from file
    model_ = TfLiteModelCreateFromFile(modelPath.c_str());
    if (!model_) {
        LOGE("Failed to load model: %s", modelPath.c_str());
        return false;
    }

    // 2. Extract cache directory from model path (for GPU shader serialization)
    // Example: /data/user/0/.../cache/YoloBall-nano-FP32.tflite -> /data/user/0/.../cache
    std::string cacheDir = "";
    size_t lastSlash = modelPath.find_last_of('/');
    if (lastSlash != std::string::npos) {
        cacheDir = modelPath.substr(0, lastSlash);
    }

    // 3. Create interpreter with delegate auto-fallback
    delegateManager_ = std::make_unique<DelegateManager>();
    TfLiteInterpreter* interp = delegateManager_->createInterpreter(model_, cacheDir, delegate, numThreads);
    if (!interp) {
        LOGE("Failed to create interpreter for: %s", modelPath.c_str());
        TfLiteModelDelete(model_);
        model_ = nullptr;
        return false;
    }

    // 3. Validate tensor shapes
    const TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(interp, 0);
    if (TfLiteTensorNumDims(inputTensor) != 4) {
        LOGE("Unexpected input tensor dims: %d (expected 4)", TfLiteTensorNumDims(inputTensor));
        release();
        return false;
    }

    // 4. Pre-allocate reusable cv::Mat buffers (ONE-TIME allocation)
    resizedMat_ = cv::Mat(INPUT_SIZE, INPUT_SIZE, CV_8UC3);
    floatMat_   = cv::Mat(INPUT_SIZE, INPUT_SIZE, CV_32FC3);

    LOGI("YoloBall loaded: %s on %s", modelPath.c_str(),
         delegateManager_->getActiveDelegateName().c_str());
    LOGI("  Input:  [1, %d, %d, 3] float32",
         TfLiteTensorDim(inputTensor, 1), TfLiteTensorDim(inputTensor, 2));

    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(interp, 0);
    LOGI("  Output: [1, %d, %d] float32",
         TfLiteTensorDim(outputTensor, 1), TfLiteTensorDim(outputTensor, 2));

    return true;
}

void YoloBallDetector::release() {
    if (delegateManager_) delegateManager_->release();   // Releases interpreter + delegate

    if (model_) {
        TfLiteModelDelete(model_);
        model_ = nullptr;
    }

    resizedMat_.release();
    floatMat_.release();
    tempResized_.release();

    LOGI("YoloBall model released");
}

std::string YoloBallDetector::getActiveDelegate() const {
    return delegateManager_ ? delegateManager_->getActiveDelegateName() : "none";
}

// ════════════════════════════════════════════════════════════════════════════
// Main Process — called once per frame
// ════════════════════════════════════════════════════════════════════════════

void YoloBallDetector::process(FrameContext& ctx, FramePool& pool) {
    TfLiteInterpreter* interp = delegateManager_ ? delegateManager_->getInterpreter() : nullptr;
    if (!interp) {
        ctx.ballVisible = false;
        return;
    }

    // 1. Acquire frame from pool (refCount++)
    FrameSlot* slot = pool.acquireLatest();
    if (!slot) {
        ctx.ballVisible = false;
        return;
    }

    // 2. Use frame data from pool slot
    ctx.originalWidth = slot->originalWidth;
    ctx.originalHeight = slot->originalHeight;
    ctx.frameId = slot->frameId;

    // ── Timing instrumentation (log every 30 frames) ──
    auto t0 = std::chrono::high_resolution_clock::now();

    // 3. Pre-process: Letterbox + Normalize → copy to TFLite input tensor
    preprocess(slot->rgb, slot->originalWidth, slot->originalHeight);

    // 4. Release frame (refCount--) — slot reusable after preprocess
    //    Pixels already copied into TFLite input tensor, original not needed
    slot->release();

    auto t1 = std::chrono::high_resolution_clock::now();

    // 5. Invoke inference
    if (TfLiteInterpreterInvoke(interp) != kTfLiteOk) {
        LOGW("TFLite invoke failed on frame %d", ctx.frameId);
        ctx.ballVisible = false;
        return;
    }

    auto t2 = std::chrono::high_resolution_clock::now();

    // 6. Post-process: Parse output → NMS → un-letterbox → write to ctx
    postprocess(ctx);

    auto t3 = std::chrono::high_resolution_clock::now();

    // Log timing every 30 frames (avoid logcat flood)
    if (ctx.frameId % 30 == 0) {
        auto preMs  = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        auto invMs  = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
        auto postMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t2).count();
        auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t0).count();
        LOGI("Frame %d timing: pre=%lldms invoke=%lldms post=%lldms total=%lldms",
             ctx.frameId, (long long)preMs, (long long)invMs, (long long)postMs, (long long)totalMs);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Pre-processing: Letterbox + Normalize (optimized)
//
// Optimizations vs naive approach:
//   1. Padding zeroed ONCE per video (not every frame) — saves ~2ms
//   2. Resize directly into ROI of resizedMat_ — saves copyTo
//   3. convertTo only on ROI region, not full 640×640 — saves ~3ms
// ════════════════════════════════════════════════════════════════════════════

void YoloBallDetector::preprocess(const cv::Mat& rgbFrame, int origW, int origH) {
    const int srcW = rgbFrame.cols;
    const int srcH = rgbFrame.rows;

    // Recalculate letterbox only if source dimensions changed
    if (srcW != lastSrcW_ || srcH != lastSrcH_) {
        letterboxScale_ = std::min(
            static_cast<float>(INPUT_SIZE) / static_cast<float>(srcW),
            static_cast<float>(INPUT_SIZE) / static_cast<float>(srcH)
        );

        lastSrcW_ = srcW;
        lastSrcH_ = srcH;
        paddingCleared_ = false;  // Force re-clear padding for new dims
    }

    const int newW = static_cast<int>(srcW * letterboxScale_);
    const int newH = static_cast<int>(srcH * letterboxScale_);
    letterboxPadX_ = (INPUT_SIZE - newW) / 2;
    letterboxPadY_ = (INPUT_SIZE - newH) / 2;

    // ── Step 1: Zero padding — only once per video resolution ──
    if (!paddingCleared_) {
        // Zero entire float buffer (padding stays zero for subsequent frames)
        floatMat_.setTo(cv::Scalar(0.0f, 0.0f, 0.0f));
        paddingCleared_ = true;
    }

    // ── Step 2: Resize directly into the ROI of resizedMat_ ──
    cv::Rect roi(letterboxPadX_, letterboxPadY_, newW, newH);
    cv::Mat roiMat = resizedMat_(roi);
    cv::resize(rgbFrame, roiMat, cv::Size(newW, newH), 0, 0, cv::INTER_LINEAR);

    // ── Step 3: Normalize ONLY the ROI [0,255] → [0.0,1.0] ──
    // This avoids converting the black padding pixels (saves ~40% work)
    cv::Mat floatRoi = floatMat_(roi);
    roiMat.convertTo(floatRoi, CV_32FC3, 1.0 / 255.0);

    // ── Step 4: Copy to TFLite input tensor ──
    TfLiteInterpreter* interp = delegateManager_->getInterpreter();
    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(interp, 0);

    // Direct memcpy — floatMat_ data layout matches NHWC [1, 640, 640, 3]
    TfLiteTensorCopyFromBuffer(
        inputTensor,
        floatMat_.data,
        INPUT_SIZE * INPUT_SIZE * 3 * sizeof(float)
    );

    // ── Debug Logging (every 30 frames) to check if tensor is empty ──
    if (lastSrcW_ % 30 == 0 || true) { // Always log for debugging locally
        double minVal, maxVal;
        cv::minMaxLoc(floatMat_.reshape(1), &minVal, &maxVal);
        const float* inData = reinterpret_cast<const float*>(TfLiteTensorData(inputTensor));
        float centerPixelR = inData[(INPUT_SIZE/2 * INPUT_SIZE + INPUT_SIZE/2) * 3 + 0];
        float centerPixelG = inData[(INPUT_SIZE/2 * INPUT_SIZE + INPUT_SIZE/2) * 3 + 1];
        float centerPixelB = inData[(INPUT_SIZE/2 * INPUT_SIZE + INPUT_SIZE/2) * 3 + 2];
        LOGI("PREPROCESS DUMP: input tensor min=%f, max=%f. Center pixel RGB=(%f, %f, %f). roi size=(%d, %d)", 
            minVal, maxVal, centerPixelR, centerPixelG, centerPixelB, newW, newH);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Post-processing: Parse [1, 5, 8400] → NMS → un-letterbox
// ════════════════════════════════════════════════════════════════════════════

void YoloBallDetector::postprocess(FrameContext& ctx) {
    TfLiteInterpreter* interp = delegateManager_->getInterpreter();
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(interp, 0);

    // Get raw output pointer — layout: [1, 5, 8400] row-major
    // Row 0 (offset 0*8400): x_center for all 8400 anchors
    // Row 1 (offset 1*8400): y_center
    // Row 2 (offset 2*8400): width
    // Row 3 (offset 3*8400): height
    // Row 4 (offset 4*8400): ball_score
    const float* output = reinterpret_cast<const float*>(TfLiteTensorData(outputTensor));

    if (ctx.frameId % 30 == 0 || true) {
        LOGI("POSTPROCESS DUMP: first 10 elements: [%f, %f, %f, %f, %f, %f, %f, %f, %f, %f]", 
            output[0], output[1], output[2], output[3], output[4], output[5], output[6], output[7], output[8], output[9]);
        LOGI("POSTPROCESS DUMP: elements at 8400*idx: [%f, %f, %f, %f, %f]", 
            output[0 * NUM_ANCHORS], output[1 * NUM_ANCHORS], output[2 * NUM_ANCHORS], output[3 * NUM_ANCHORS], output[4 * NUM_ANCHORS]);
    }

    // ── Step 1: Filter by confidence threshold ──
    std::vector<Detection> candidates;
    candidates.reserve(MAX_CANDIDATES);

    float maxScore = 0.0f;

    for (int i = 0; i < NUM_ANCHORS; ++i) {
        const float score = output[4 * NUM_ANCHORS + i];   // Row 4
        
        if (score > maxScore) maxScore = score;

        if (score > CONF_THRESHOLD) {
            Detection det;
            // Scale normalized [0..1] coordinates to absolute [0..INPUT_SIZE] tensor coordinates
            det.cx    = output[0 * NUM_ANCHORS + i] * INPUT_SIZE;       // Row 0
            det.cy    = output[1 * NUM_ANCHORS + i] * INPUT_SIZE;       // Row 1
            det.w     = output[2 * NUM_ANCHORS + i] * INPUT_SIZE;       // Row 2
            det.h     = output[3 * NUM_ANCHORS + i] * INPUT_SIZE;       // Row 3
            det.score = score;
            det.classId = 0;
            candidates.push_back(det);
        }
    }

    if (ctx.frameId % 30 == 0) {
        LOGI("Frame %d max ball score: %f", ctx.frameId, maxScore);
    }

    if (candidates.empty()) {
        ctx.ballVisible = false;
        return;
    }

    // ── Step 2: Sort by confidence descending ──
    std::sort(candidates.begin(), candidates.end(),
        [](const Detection& a, const Detection& b) { return a.score > b.score; });

    // ── Step 3: Apply NMS ──
    std::vector<Detection> kept;
    nms(candidates, IOU_THRESHOLD, kept);

    if (kept.empty()) {
        ctx.ballVisible = false;
        return;
    }

    // ── Step 4: Best detection → un-letterbox to original coordinates ──
    const Detection& best = kept[0];

    // Remove letterbox padding, then undo scale
    const float rawX = best.cx - static_cast<float>(letterboxPadX_);
    const float rawY = best.cy - static_cast<float>(letterboxPadY_);

    const float finalX = rawX / letterboxScale_;
    const float finalY = rawY / letterboxScale_;
    const float finalW = best.w / letterboxScale_;
    const float finalH = best.h / letterboxScale_;

    // Clamp: negative coords = detection in letterbox padding = false positive
    if (finalX < 0 || finalY < 0 ||
        finalX > lastSrcW_ || finalY > lastSrcH_) {
        LOGW("Detection thrown out because out of bounds! rawX=%f rawY=%f finalX=%f finalY=%f srcW=%d srcH=%d scale=%f", rawX, rawY, finalX, finalY, lastSrcW_, lastSrcH_, letterboxScale_);
        ctx.ballVisible = false;
        return;
    }

    // Scale from pool dimensions (e.g. 1280x720) back to original video dimensions (e.g. 1920x1080)
    float scaleX = static_cast<float>(ctx.originalWidth) / static_cast<float>(lastSrcW_);
    float scaleY = static_cast<float>(ctx.originalHeight) / static_cast<float>(lastSrcH_);

    ctx.ballVisible = true;
    ctx.ballX       = finalX * scaleX;
    ctx.ballY       = finalY * scaleY;
    ctx.ballW       = finalW * scaleX;
    ctx.ballH       = finalH * scaleY;
    ctx.ballScore   = best.score;

    if (ctx.frameId % 30 == 0) {
        LOGI("Ball DETECTED Frame %d! x=%f, y=%f, w=%f, h=%f, score=%f", ctx.frameId, ctx.ballX, ctx.ballY, ctx.ballW, ctx.ballH, ctx.ballScore);
    }
}

// ════════════════════════════════════════════════════════════════════════════
// NMS (Non-Maximum Suppression)
// ════════════════════════════════════════════════════════════════════════════

float YoloBallDetector::computeIoU(const Detection& a, const Detection& b) {
    // Convert center-format → corner-format
    const float ax1 = a.cx - a.w * 0.5f;
    const float ay1 = a.cy - a.h * 0.5f;
    const float ax2 = a.cx + a.w * 0.5f;
    const float ay2 = a.cy + a.h * 0.5f;

    const float bx1 = b.cx - b.w * 0.5f;
    const float by1 = b.cy - b.h * 0.5f;
    const float bx2 = b.cx + b.w * 0.5f;
    const float by2 = b.cy + b.h * 0.5f;

    // Intersection
    const float interW = std::max(0.0f, std::min(ax2, bx2) - std::max(ax1, bx1));
    const float interH = std::max(0.0f, std::min(ay2, by2) - std::max(ay1, by1));
    const float interArea = interW * interH;

    // Union
    const float unionArea = a.w * a.h + b.w * b.h - interArea;
    return unionArea > 0.0f ? interArea / unionArea : 0.0f;
}

void YoloBallDetector::nms(std::vector<Detection>& candidates, float iouThreshold, std::vector<Detection>& kept) {
    kept.clear();
    std::vector<bool> suppressed(candidates.size(), false);

    for (size_t i = 0; i < candidates.size(); ++i) {
        if (suppressed[i]) continue;

        kept.push_back(candidates[i]);

        // Suppress all lower-confidence detections that overlap
        for (size_t j = i + 1; j < candidates.size(); ++j) {
            if (!suppressed[j] && computeIoU(candidates[i], candidates[j]) > iouThreshold) {
                suppressed[j] = true;
            }
        }
    }
}

}