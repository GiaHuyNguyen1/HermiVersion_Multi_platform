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
 * MobileNetV3-Small Court Keypoint Detector — TFLite C API implementation.
 *
 * Model I/O:
 *   Input:  float32[1, 480, 480, 3]  — NHWC, ImageNet normalized
 *   Output: float32[1, 28]           — 14 keypoints × 2 (x, y), sigmoid [0,1]
 *
 * Preprocessing (differs from YOLO):
 *   1. Simple resize (stretch, NO letterbox) → 480×480
 *   2. ImageNet normalize: (pixel/255 - mean) / std
 *      Mean = [0.485, 0.456, 0.406] (RGB)
 *      Std  = [0.229, 0.224, 0.225] (RGB)
 *
 * Postprocessing:
 *   x_px = output[i*2]   * originalWidth
 *   y_px = output[i*2+1] * originalHeight
 *
 * Zero-allocation design:
 *   - All cv::Mat buffers pre-allocated in loadModel()
 *   - No new/malloc in the inference loop
 */
class CourtDetector : public IAIModel {
public:
    // Model constants
    static constexpr int INPUT_SIZE = 480;
    static constexpr int NUM_KEYPOINTS = 14;
    static constexpr int NUM_OUTPUTS = 28;     // 14 × 2

    // ImageNet normalization constants (RGB order)
    static constexpr float MEAN[3] = {0.485f, 0.456f, 0.406f};
    static constexpr float STD[3]  = {0.229f, 0.224f, 0.225f};

    CourtDetector() = default;
    ~CourtDetector() override { release(); }

    bool loadModel(const std::string& modelPath, DelegateType delegate, int numThreads) override;
    void process(FrameContext& ctx, FramePool& pool) override;
    void release() override;
    std::string getActiveDelegate() const override;

private:
    // TFLite (C API)
    TfLiteModel* model_ = nullptr;
    std::unique_ptr<IDelegateManager> delegateManager_;

    // ── Pre-allocated buffers (created once in loadModel, reused every frame) ──
    cv::Mat resizedMat_;     // 480×480 (uint8)
    cv::Mat floatMat_;       // 480×480 (float32, ImageNet normalized)

    // ── Pre-processing ──
    void preprocess(const cv::Mat& rgbFrame, int origW, int origH);

    // ── Post-processing ──
    void postprocess(FrameContext& ctx);
};

} // namespace hermivision
