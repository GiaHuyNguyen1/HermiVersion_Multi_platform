#include "tracker.h"
#include <android/log.h>

#define LOG_TAG "BallTracker"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace hermivision {

// ════════════════════════════════════════════════════════════════════════════
// Initialize Kalman Filter
//
// State:       [x, y, vx, vy]   (4 dimensions)
// Measurement: [x, y]           (2 dimensions)
// Model: constant velocity — ball moves linearly between frames
// ════════════════════════════════════════════════════════════════════════════

void BallTracker::initKF(float x, float y) {
    // 4 state vars (x, y, vx, vy), 2 measurement vars (x, y), 0 control vars
    kf_ = cv::KalmanFilter(4, 2, 0);

    // ── Transition matrix (constant velocity model) ──
    // | x' |   | 1 0 1 0 |   | x  |
    // | y' | = | 0 1 0 1 | × | y  |
    // | vx'|   | 0 0 1 0 |   | vx |
    // | vy'|   | 0 0 0 1 |   | vy |
    kf_.transitionMatrix = (cv::Mat_<float>(4, 4) <<
        1, 0, 1, 0,
        0, 1, 0, 1,
        0, 0, 1, 0,
        0, 0, 0, 1
    );

    // ── Measurement matrix — we only observe (x, y) ──
    kf_.measurementMatrix = cv::Mat::zeros(2, 4, CV_32F);
    kf_.measurementMatrix.at<float>(0, 0) = 1.0f;
    kf_.measurementMatrix.at<float>(1, 1) = 1.0f;

    // ── Process noise (how much we trust the constant-velocity model) ──
    // Small values = smooth trajectory, large values = responsive to changes
    cv::setIdentity(kf_.processNoiseCov, cv::Scalar(1e-2));

    // ── Measurement noise (how much we trust YOLO detections) ──
    // YOLO has pixel-level noise, so moderate values work well
    cv::setIdentity(kf_.measurementNoiseCov, cv::Scalar(1e-1));

    // ── Error covariance (initial uncertainty — high because first detection) ──
    cv::setIdentity(kf_.errorCovPost, cv::Scalar(1.0));

    // ── Initial state ──
    kf_.statePost.at<float>(0) = x;    // x position
    kf_.statePost.at<float>(1) = y;    // y position
    kf_.statePost.at<float>(2) = 0.0f; // vx (unknown initially)
    kf_.statePost.at<float>(3) = 0.0f; // vy (unknown initially)
}

void BallTracker::reset() {
    initialized_ = false;
    missCount_ = 0;
}

void BallTracker::update(float x, float y) {
    if (!initialized_) {
        initKF(x, y);
        initialized_ = true;
        missCount_ = 0;
        LOGI("Tracker initialized at (%.1f, %.1f)", x, y);
        return;
    }

    // Predict step (advance state by one timestep)
    kf_.predict();

    // Correct step (incorporate real YOLO measurement)
    cv::Mat measurement = (cv::Mat_<float>(2, 1) << x, y);
    kf_.correct(measurement);

    missCount_ = 0;
}

cv::Point2f BallTracker::predict() {
    if (!initialized_) {
        return {0.0f, 0.0f};
    }

    // Predict next state without a measurement
    cv::Mat predicted = kf_.predict();

    missCount_++;

    if (missCount_ >= MAX_MISS_FRAMES) {
        LOGI("Tracker lost (>%d consecutive misses), resetting", MAX_MISS_FRAMES);
        reset();
        return {0.0f, 0.0f};
    }

    return {predicted.at<float>(0), predicted.at<float>(1)};
}

} // namespace hermivision
