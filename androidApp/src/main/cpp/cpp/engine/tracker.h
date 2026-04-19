#pragma once

#include <opencv2/video/tracking.hpp>
#include <opencv2/core.hpp>

namespace hermivision {

/**
 * Ball Tracker using OpenCV Kalman Filter (NOT an AI model).
 *
 * This is a pure mathematical predictor that uses linear algebra
 * to estimate ball position when YOLO detection fails (occlusion,
 * motion blur, etc.).
 *
 * State vector: [x, y, vx, vy] (position + velocity)
 * Measurement:  [x, y]          (detected position from YOLO)
 *
 * Usage:
 *   tracker.reset();
 *   // Every frame:
 *   if (yolo_detected) {
 *       tracker.update(detectedX, detectedY);
 *   } else {
 *       auto predicted = tracker.predict();
 *       // Use predicted.x, predicted.y as fallback
 *   }
 */
class BallTracker {
public:
    BallTracker() = default;

    /// Initialize/reset the Kalman Filter state
    void reset();

    /// Update tracker with a real YOLO detection (correct + predict)
    /// Resets the miss counter.
    void update(float x, float y);

    /// Predict next position without a detection.
    /// Returns the predicted (x, y). Increments miss counter.
    cv::Point2f predict();

    /// Check if the tracker has lost tracking (too many consecutive misses)
    bool isLost() const { return missCount_ >= MAX_MISS_FRAMES; }

    /// Check if tracker has been initialized with at least one detection
    bool isInitialized() const { return initialized_; }

    /// Get consecutive miss count
    int getMissCount() const { return missCount_; }

private:
    cv::KalmanFilter kf_;
    bool initialized_ = false;
    int missCount_ = 0;

    // Maximum consecutive frames without detection before reset
    // Approved by user: 5 frames
    static constexpr int MAX_MISS_FRAMES = 5;

    /// Internal: initialize Kalman Filter matrices
    void initKF(float x, float y);
};

} // namespace hermivision
