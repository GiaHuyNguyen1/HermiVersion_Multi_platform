#include "hermivision_pipeline.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "HermiPipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

bool HermiVisionPipeline::init(const PipelineConfig& config) {
    config_ = config;

    // ── Initialize FramePool (pre-allocate ring buffer) ──
    framePool_.init(1280, 720);

    // ── Initialize Ball Detector ──
    ballDetector_ = std::make_unique<YoloBallDetector>();
    if (!ballDetector_->loadModel(config.ballModelPath, config.delegate, config.numThreads)) {
        LOGE("Failed to load ball detection model: %s", config.ballModelPath.c_str());
        ballDetector_.reset();
        return false;
    }

    // ── Initialize Ball Tracker (Kalman Filter — pure math) ──
    tracker_.reset();

    // ── Initialize Court Detector (optional — only if model path provided) ──
    if (!config.courtModelPath.empty()) {
        courtDetector_ = std::make_unique<CourtDetector>();
        if (!courtDetector_->loadModel(config.courtModelPath, config.courtDelegate, config.numThreads)) {
            LOGE("Failed to load court model: %s — court detection disabled", config.courtModelPath.c_str());
            courtDetector_.reset();
            // Non-fatal: pipeline continues without court detection
        } else {
            // Start background court detection thread
            courtRunning_.store(true);
            courtThread_ = std::thread(&HermiVisionPipeline::courtDetectionLoop, this);
            LOGI("Court detection thread started (interval=%d frames)", COURT_DETECT_INTERVAL);
        }
    }

    initialized_ = true;
    LOGI("═══════════════════════════════════════════════");
    LOGI("Pipeline initialized successfully");
    LOGI("  Ball model: %s", config.ballModelPath.c_str());
    LOGI("  Delegate:   %s", ballDetector_->getActiveDelegate().c_str());
    LOGI("  Threads:    %d", config.numThreads);
    LOGI("  Tracker:    Kalman Filter (max_miss=%d)", 5);
    LOGI("  FramePool:  %d slots (%.1f MB)",
         FramePool::POOL_SIZE, FramePool::POOL_SIZE * 1280.0 * 720.0 * 3.0 / (1024.0 * 1024.0));
    if (courtDetector_) {
        LOGI("  Court model: %s on %s (every %d frames)",
             config.courtModelPath.c_str(),
             courtDetector_->getActiveDelegate().c_str(),
             COURT_DETECT_INTERVAL);
    } else if (!config.courtModelPath.empty()) {
        LOGI("  Court model: FAILED to load");
    } else {
        LOGI("  Court model: disabled (no path)");
    }
    LOGI("═══════════════════════════════════════════════");

    return true;
}

// ════════════════════════════════════════════════════════════════════════════
// Submit RGB frame to pool — called from OpenCV Mat producers
// ════════════════════════════════════════════════════════════════════════════

void HermiVisionPipeline::submitFrame(const cv::Mat& rgbFrame, int frameId, int origW, int origH) {
    FrameSlot* slot = framePool_.acquireForWrite();
    if (!slot) {
        LOGE("submitFrame: no writable slot available (frame %d dropped)", frameId);
        return;
    }

    if (rgbFrame.cols == slot->rgb.cols && rgbFrame.rows == slot->rgb.rows) {
        rgbFrame.copyTo(slot->rgb);
    } else {
        cv::resize(rgbFrame, slot->rgb, slot->rgb.size());
    }

    slot->frameId = frameId;
    slot->originalWidth = origW;
    slot->originalHeight = origH;
    slot->ready.store(true, std::memory_order_release);
}

// ════════════════════════════════════════════════════════════════════════════
// Submit YUV frame to pool — zero-copy path from decoder/camera
// YUV→RGB conversion via PreProcessor (NEON SIMD optimized)
// ════════════════════════════════════════════════════════════════════════════

void HermiVisionPipeline::submitYuvFrame(
    const uint8_t* yData, const uint8_t* uvData,
    int width, int height,
    int yStride, int uvStride,
    int frameId
) {
    FrameSlot* slot = framePool_.acquireForWrite();
    if (!slot) {
        LOGE("submitYuvFrame: no writable slot (frame %d dropped)", frameId);
        return;
    }

    // YUV→RGB conversion into a temp Mat, then copy/resize into pool slot
    cv::Mat tempRgb;
    PreProcessor::yuvToRgb(yData, uvData, width, height, yStride, uvStride, tempRgb);

    if (tempRgb.cols == slot->rgb.cols && tempRgb.rows == slot->rgb.rows) {
        tempRgb.copyTo(slot->rgb);
    } else {
        cv::resize(tempRgb, slot->rgb, slot->rgb.size());
    }

    slot->frameId = frameId;
    slot->originalWidth = width;
    slot->originalHeight = height;
    slot->ready.store(true, std::memory_order_release);
}

// ════════════════════════════════════════════════════════════════════════════
// Process latest frame from FramePool through all AI models
// This is the MAIN processing entry point (replaces legacy processFrame)
// ════════════════════════════════════════════════════════════════════════════

FrameResult HermiVisionPipeline::processLatestFrame() {
    FrameResult result{};

    if (!initialized_ || !ballDetector_) {
        return result;
    }

    // ── Build FrameContext ──
    FrameContext ctx;

    // ── Stage 1: Ball Detection (YOLO — runs every frame) ──
    // YOLO acquires frame from pool internally via process(ctx, pool)
    ballDetector_->process(ctx, framePool_);

    result.frameId = ctx.frameId;

    // ── Stage 2: Kalman Tracker (smooth trajectory, fill occlusion gaps) ──
    if (ctx.ballVisible) {
        tracker_.update(ctx.ballX, ctx.ballY);
    } else {
        if (tracker_.isInitialized() && !tracker_.isLost()) {
            cv::Point2f predicted = tracker_.predict();
            if (!tracker_.isLost()) {
                ctx.ballVisible = true;
                ctx.ballX = predicted.x;
                ctx.ballY = predicted.y;
                ctx.ballScore = 0.1f;
            }
        }
    }

    // ── Stage 3: Read cached court result (from background thread) ──
    {
        std::lock_guard<std::mutex> lock(courtMutex_);
        result.courtValid = cachedCourt_.valid;
        if (cachedCourt_.valid) {
            std::memcpy(result.courtKeypoints, cachedCourt_.points, sizeof(float) * 28);
        }
    }

    // ── Pack ball results for Kotlin ──
    result.ballVisible = ctx.ballVisible;
    result.ballX       = ctx.ballX;
    result.ballY       = ctx.ballY;
    result.ballW       = ctx.ballW;
    result.ballH       = ctx.ballH;
    result.ballScore   = ctx.ballScore;

    return result;
}

// ════════════════════════════════════════════════════════════════════════════
// Court Detection Background Thread
// Runs court model every COURT_DETECT_INTERVAL frames, updates cachedCourt_
// ════════════════════════════════════════════════════════════════════════════

void HermiVisionPipeline::courtDetectionLoop() {
    LOGI("Court detection thread started");
    int lastProcessedFrame = -1;

    while (courtRunning_.load(std::memory_order_relaxed)) {
        int currentFrame = framePool_.getWriteCount();

        // Only run when a new interval boundary is reached
        int targetFrame = (currentFrame / COURT_DETECT_INTERVAL) * COURT_DETECT_INTERVAL;
        if (targetFrame <= lastProcessedFrame || currentFrame < COURT_DETECT_INTERVAL) {
            // No new interval yet — sleep briefly to avoid busy-wait
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        // Run court detection on latest frame
        FrameContext courtCtx;
        courtDetector_->process(courtCtx, framePool_);

        if (courtCtx.courtKeypoints.valid) {
            std::lock_guard<std::mutex> lock(courtMutex_);
            cachedCourt_ = courtCtx.courtKeypoints;
        }

        lastProcessedFrame = targetFrame;
    }

    LOGI("Court detection thread exited");
}

// ════════════════════════════════════════════════════════════════════════════
// Release
// ════════════════════════════════════════════════════════════════════════════

void HermiVisionPipeline::release() {
    // Stop court thread first (must join before destroying detector)
    if (courtRunning_.load()) {
        courtRunning_.store(false);
        if (courtThread_.joinable()) {
            courtThread_.join();
        }
        LOGI("Court detection thread joined");
    }

    if (courtDetector_) {
        courtDetector_->release();
        courtDetector_.reset();
    }

    if (ballDetector_) {
        ballDetector_->release();
        ballDetector_.reset();
    }
    tracker_.reset();
    framePool_.release();

    initialized_ = false;
    LOGI("Pipeline released");
}

std::string HermiVisionPipeline::getActiveDelegate() const {
    if (ballDetector_) {
        return ballDetector_->getActiveDelegate();
    }
    return "none";
}

}