#include "hermivision_pipeline.h"
#include <android/log.h>
#include <cstring>
#include <chrono>

#define LOG_TAG "HermiPipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

bool HermiVisionPipeline::init(const PipelineConfig& config) {
    config_ = config;

    framePool_.init(1280, 720);

    ballDetector_ = std::make_unique<YoloBallDetector>();
    if (!ballDetector_->loadModel(config.ballModelPath, config.delegate, config.numThreads)) {
        LOGE("Failed to load ball detection model: %s", config.ballModelPath.c_str());
        ballDetector_.reset();
        return false;
    }

    tracker_.reset();

    if (!config.courtModelPath.empty()) {
        courtDetector_ = std::make_unique<CourtDetector>();
        if (!courtDetector_->loadModel(config.courtModelPath, config.courtDelegate, config.numThreads)) {
            LOGE("Failed to load court model: %s — court detection disabled", config.courtModelPath.c_str());
            courtDetector_.reset();
        } else {
            courtRunning_.store(true);
            courtThread_ = std::thread(&HermiVisionPipeline::courtDetectionLoop, this);
            LOGI("Court detection thread started (interval=%dms)", COURT_DETECT_INTERVAL_MS);
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
        LOGI("  Court model: %s on %s (every %dms)",
             config.courtModelPath.c_str(),
             courtDetector_->getActiveDelegate().c_str(),
             COURT_DETECT_INTERVAL_MS);
    } else if (!config.courtModelPath.empty()) {
        LOGI("  Court model: FAILED to load");
    } else {
        LOGI("  Court model: disabled (no path)");
    }
    LOGI("═══════════════════════════════════════════════");

    return true;
}

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

FrameResult HermiVisionPipeline::processLatestFrame() {
    FrameResult result{};

    if (!initialized_ || !ballDetector_) {
        return result;
    }

    FrameContext ctx;

    ballDetector_->process(ctx, framePool_);

    result.frameId = ctx.frameId;

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

    {
        std::lock_guard<std::mutex> lock(courtMutex_);
        result.courtValid = cachedCourt_.valid;
        if (cachedCourt_.valid) {
            std::memcpy(result.courtKeypoints, cachedCourt_.points, sizeof(float) * 28);
        }
    }

    result.ballVisible = ctx.ballVisible;
    result.ballX       = ctx.ballX;
    result.ballY       = ctx.ballY;
    result.ballW       = ctx.ballW;
    result.ballH       = ctx.ballH;
    result.ballScore   = ctx.ballScore;

    return result;
}

void HermiVisionPipeline::courtDetectionLoop() {
    LOGI("Court detection thread started (interval=%dms)", COURT_DETECT_INTERVAL_MS);
    auto lastRunTime = std::chrono::steady_clock::now() -
                       std::chrono::milliseconds(COURT_DETECT_INTERVAL_MS);

    while (courtRunning_.load(std::memory_order_relaxed)) {
        auto now = std::chrono::steady_clock::now();
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastRunTime).count();

        if (elapsedMs < COURT_DETECT_INTERVAL_MS) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        FrameContext courtCtx;
        courtDetector_->process(courtCtx, framePool_);

        if (courtCtx.courtKeypoints.valid) {
            std::lock_guard<std::mutex> lock(courtMutex_);
            cachedCourt_ = courtCtx.courtKeypoints;
        }

        lastRunTime = std::chrono::steady_clock::now();
        LOGI("Court detection ran at %lldms since last run", (long long)elapsedMs);
    }

    LOGI("Court detection thread exited");
}


void HermiVisionPipeline::release() {
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