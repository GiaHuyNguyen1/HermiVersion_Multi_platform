#pragma once

#include "types.h"
#include "frame_pool.h"
#include "preprocessor.h"
#include "tracker.h"
#include "../models/yolo_ball_detector.h"
#include "../models/court_detector.h"
#include <memory>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>

namespace hermivision {

/**
 * HermiVision Pipeline — orchestrates all AI models for a single frame.
 *
 * Current:
 *   - Ball Detection (YOLO — every frame, GPU delegate)
 *   - Kalman Tracker (smooth trajectory, fill occlusion gaps)
 *   - Court Detection (MobileNetV3 — every 30 frames, background thread, CPU delegate)
 *
 * Lifecycle:
 *   1. init(config)              — called once from JNI initPipeline()
 *   2. submitFrame(rgbMat, ...)  — write RGB frame to pool (from OpenCV Mat)
 *      submitYuvFrame(...)       — write YUV frame to pool (from decoder/camera)
 *   3. processLatestFrame()      — process latest frame through all models
 *   4. release()                 — called from JNI releasePipeline()
 *
 * Threading:
 *   - JNI thread: decode → submitFrame → processLatestFrame (YOLO + read cachedCourt)
 *   - Court thread: courtDetectionLoop() — sleeps, wakes every 30 frames, runs court model
 */
class HermiVisionPipeline {
public:
    HermiVisionPipeline() = default;
    ~HermiVisionPipeline() { release(); }

    /// Initialize pipeline with config from Kotlin AIConfig
    bool init(const PipelineConfig& config);

    /// Submit RGB frame to pool (from OpenCV Mat — e.g. legacy callers)
    void submitFrame(const cv::Mat& rgbFrame, int frameId, int origW, int origH);

    /// Submit YUV frame to pool (from decoder/camera — zero-copy path)
    /// YUV→RGB conversion happens in C++ via PreProcessor (NEON SIMD)
    void submitYuvFrame(const uint8_t* yData, const uint8_t* uvData,
                        int width, int height,
                        int yStride, int uvStride,
                        int frameId);

    /// Process latest frame from FramePool through all models
    FrameResult processLatestFrame();

    /// Release all model resources
    void release();

    /// Query active hardware delegate
    std::string getActiveDelegate() const;

    /// Access FramePool (for producers that need direct write)
    FramePool& getFramePool() { return framePool_; }

private:
    PipelineConfig config_;

    // ── Core Infrastructure ──
    FramePool framePool_;

    // ── AI Models ──
    std::unique_ptr<YoloBallDetector> ballDetector_;
    std::unique_ptr<CourtDetector> courtDetector_;

    // ── Ball Tracker (Kalman Filter — pure math, not AI) ──
    BallTracker tracker_;

    // ── Court Detection Async (background thread) ──
    std::thread courtThread_;
    std::atomic<bool> courtRunning_{false};

    // Thread-safe cached court result
    CourtKeypoints cachedCourt_;
    mutable std::mutex courtMutex_;

    static constexpr int COURT_DETECT_INTERVAL = 30;

    /// Background thread: runs court detection every N frames
    void courtDetectionLoop();

    bool initialized_ = false;
};

}