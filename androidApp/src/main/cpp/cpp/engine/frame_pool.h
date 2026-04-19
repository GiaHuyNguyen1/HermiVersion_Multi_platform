#pragma once

#include <opencv2/core.hpp>
#include <atomic>
#include <mutex>
#include <cstdint>

namespace hermivision {

/**
 * FrameSlot — A single pre-allocated frame buffer in the ring pool.
 *
 * Lifecycle:
 *   Producer:  acquireForWrite() → writes pixels → marks ready
 *   Consumer:  acquireLatest() → reads pixels → release()
 *
 * Reference counting ensures slots aren't overwritten while in use.
 */
struct FrameSlot {
    cv::Mat rgb;                    // Pre-allocated pixel buffer (maxW × maxH × 3)
    int frameId = -1;
    int64_t timestampMs = 0;
    int originalWidth = 0;
    int originalHeight = 0;
    std::atomic<int> refCount{0};   // Reference counting for lifecycle
    std::atomic<bool> ready{false}; // Has valid data

    void acquire() { refCount.fetch_add(1, std::memory_order_relaxed); }
    bool release() { return refCount.fetch_sub(1, std::memory_order_acq_rel) == 1; }
};

/**
 * FramePool — Lock-free ring buffer for zero-allocation frame sharing.
 *
 * Design:
 *   - Pre-allocated: init() mallocs all pixel buffers once. Runtime = zero allocation.
 *   - Reference counted: Consumers acquire/release slots. When refCount=0, slot reusable.
 *   - Lock-free write: Single producer uses atomic writeIdx (no mutex).
 *   - Thread-safe read: Multiple consumers can call acquireLatest() concurrently.
 *   - Input-agnostic: Works with any producer (video decoder, camera, RTSP stream).
 *
 * Memory budget: POOL_SIZE × maxWidth × maxHeight × 3 bytes
 *   Example: 5 × 1280 × 720 × 3 = 13.2 MB fixed
 */
class FramePool {
public:
    static constexpr int POOL_SIZE = 5;

    FramePool() = default;
    ~FramePool() { release(); }

    /// Pre-allocate all slots (call once at pipeline init)
    void init(int maxWidth, int maxHeight);

    /// Producer: get next writable slot (decoder/camera thread)
    /// Skips slots with active references (refCount > 0).
    FrameSlot* acquireForWrite();

    /// Consumer: get most recent ready frame (any model thread)
    /// Returns nullptr if no frame available. Caller MUST call slot->release() when done.
    FrameSlot* acquireLatest();

    /// Consumer: get specific frame by ID (for historical access)
    /// Returns nullptr if frame not found or expired. Caller MUST call slot->release() when done.
    FrameSlot* acquireByFrameId(int frameId);

    /// Release all resources
    void release();

    /// Stats
    int getWriteCount() const { return writeCount_.load(std::memory_order_relaxed); }

    /// Check if pool has been initialized
    bool isInitialized() const { return initialized_; }

private:
    FrameSlot slots_[POOL_SIZE];
    std::atomic<int> writeIdx_{0};
    std::atomic<int> writeCount_{0};
    bool initialized_ = false;
};

}