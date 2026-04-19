#pragma once

#include "../engine/interfaces/i_ai_model.h"
#include "../engine/interfaces/i_delegate_manager.h"
#include "../engine/frame_pool.h"
#include "../engine/delegate_manager.h"
#include "tensorflow/lite/c/c_api.h"
#include <memory>
#include <opencv2/core.hpp>

namespace hermivision {

/**
 * YOLO26-nano Ball Detector — TFLite C API implementation.
 *
 * Model I/O (Ultralytics universal format, end2end=False):
 *   Input:  float32[1, 640, 640, 3]  — NHWC, normalized [0,1], RGB
 *   Output: float32[1, 5, 8400]      — [x_center, y_center, w, h, score] × 8400 anchors
 *
 * Pipeline per frame:
 *   1. Preprocess: Letterbox resize (preserve aspect ratio) + normalize [0,1]
 *   2. TFLite invoke (with NNAPI/GPU/CPU delegate)
 *   3. Postprocess: confidence filter → NMS → un-letterbox → FrameContext
 *
 * Zero-allocation design:
 *   - All cv::Mat buffers pre-allocated in loadModel()
 *   - No new/malloc in the inference loop
 *   - Output parsed directly from TFLite tensor pointer
 */
class YoloBallDetector : public IAIModel {
public:
    // Model constants
    static constexpr int INPUT_SIZE    = 640;
    static constexpr int NUM_ANCHORS   = 8400;   // 80×80 + 40×40 + 20×20
    static constexpr int NUM_VALUES    = 5;       // x, y, w, h, score
    static constexpr float CONF_THRESHOLD = 0.25f;
    static constexpr float IOU_THRESHOLD  = 0.45f;
    static constexpr int MAX_CANDIDATES   = 100;

    YoloBallDetector() = default;
    ~YoloBallDetector() override { release(); }

    bool loadModel(const std::string& modelPath, DelegateType delegate, int numThreads) override;
    void process(FrameContext& ctx, FramePool& pool) override;
    void release() override;
    std::string getActiveDelegate() const override;

private:
    // TFLite (C API)
    TfLiteModel* model_ = nullptr;
    std::unique_ptr<IDelegateManager> delegateManager_;

    // ── Pre-allocated buffers (created once in loadModel, reused every frame) ──
    cv::Mat resizedMat_;     // 640×640 letterboxed frame (uint8)
    cv::Mat floatMat_;       // 640×640 normalized frame (float32)
    cv::Mat tempResized_;    // Intermediate resize result

    // Letterbox state (updated each frame)
    float letterboxScale_ = 1.0f;
    int letterboxPadX_ = 0;
    int letterboxPadY_ = 0;

    // Optimization: track source dims to avoid redundant padding clears
    int lastSrcW_ = 0;
    int lastSrcH_ = 0;
    bool paddingCleared_ = false;

    // ── Pre-processing ──
    void preprocess(const cv::Mat& rgbFrame, int origW, int origH);

    // ── Post-processing ──
    void postprocess(FrameContext& ctx);

    // ── NMS helpers ──
    static float computeIoU(const Detection& a, const Detection& b);
    static void nms(std::vector<Detection>& candidates, float iouThreshold,
                    std::vector<Detection>& kept);
};

}