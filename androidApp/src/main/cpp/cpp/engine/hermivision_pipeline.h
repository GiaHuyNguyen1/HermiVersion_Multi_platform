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

class HermiVisionPipeline {
public:
    HermiVisionPipeline() = default;
    ~HermiVisionPipeline() { release(); }

    bool init(const PipelineConfig& config);

    void submitFrame(const cv::Mat& rgbFrame, int frameId, int origW, int origH);

    void submitYuvFrame(const uint8_t* yData, const uint8_t* uvData,
                        int width, int height,
                        int yStride, int uvStride,
                        int frameId);

    FrameResult processLatestFrame();

    void release();

    std::string getActiveDelegate() const;

    FramePool& getFramePool() { return framePool_; }

private:
    PipelineConfig config_;

    FramePool framePool_;

    std::unique_ptr<YoloBallDetector> ballDetector_;
    std::unique_ptr<CourtDetector> courtDetector_;

    BallTracker tracker_;

    std::thread courtThread_;
    std::atomic<bool> courtRunning_{false};

    CourtKeypoints cachedCourt_;
    mutable std::mutex courtMutex_;

    static constexpr int COURT_DETECT_INTERVAL_MS = 5000;

    void courtDetectionLoop();

    bool initialized_ = false;
};

}