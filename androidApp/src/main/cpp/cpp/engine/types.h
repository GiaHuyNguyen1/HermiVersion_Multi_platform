#pragma once

#include <opencv2/core.hpp>
#include <string>

namespace hermivision {

// ───── Detection result from any model ─────
struct Detection {
    float cx{}, cy{}, w{}, h{};   // center-format bbox (letterbox 640×640 space)
    float score{};
    int classId = 0;
};

// ───── Court keypoint result (14 points) ─────
struct CourtKeypoints {
    static constexpr int NUM_POINTS = 14;
    float points[NUM_POINTS * 2] = {};  // [x1,y1,...,x14,y14] original coords
    bool valid = false;
};

// ───── Lightweight frame record for history (NO raw pixels) ─────
struct FrameRecord {
    int frameId = -1;
    int64_t timestampMs = 0;
    bool ballVisible = false;
    float ballX = 0, ballY = 0, ballScore = 0;
    CourtKeypoints court;
};

// ───── Pipeline context — passed by reference through all models (zero-copy) ─────
struct FrameContext {
    cv::Mat rgbImage;        // Current frame (RGB, original resolution)
    int originalWidth = 0;
    int originalHeight = 0;
    int frameId = 0;

    // Ball Detection output
    bool ballVisible = false;
    float ballX = 0.0f;     // Original-frame coordinate (center)
    float ballY = 0.0f;     // Original-frame coordinate (center)
    float ballW = 0.0f;     // Original-frame bbox width
    float ballH = 0.0f;     // Original-frame bbox height
    float ballScore = 0.0f;

    // ── Court Detection output ──
    CourtKeypoints courtKeypoints;

    // ── Future: Trajectory for Bounce AI ──
    // std::deque<cv::Point2f> trajectory;

    // ── Future: Bounce Detection output ──
    // bool bounceDetected = false;
    // cv::Point2f bouncePoint;
};

// ───── Hardware delegate preference ─────
// Ordinal values match Kotlin DelegateType.ordinal (NNAPI=0, GPU=1, CPU=2)
enum class DelegateType : int {
    NNAPI = 0,
    GPU   = 1,
    CPU   = 2
};

// ───── Pipeline configuration (received from Kotlin AIConfig via JNI) ─────
struct PipelineConfig {
    DelegateType delegate = DelegateType::CPU;
    int numThreads = 4;
    std::string ballModelPath;
    std::string courtModelPath;    // Empty = court detection disabled
    DelegateType courtDelegate = DelegateType::CPU;  // Separate delegate for court model
    // Future: std::string bounceModelPath;
};

// ───── Result sent back to Kotlin (per-frame) ─────
struct FrameResult {
    int frameId;
    bool ballVisible;
    float ballX, ballY;
    float ballW, ballH;     // Bbox dimensions in original-frame coords
    float ballScore;
    bool courtValid = false;
    float courtKeypoints[28] = {};  // 14 keypoints × 2 (x, y)
};

}