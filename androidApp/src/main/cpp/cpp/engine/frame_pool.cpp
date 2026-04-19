#include "frame_pool.h"

#include <android/log.h>

#define LOG_TAG "FramePool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

// ════════════════════════════════════════════════════════════════════════════
// Initialization — ONE-TIME allocation of all pixel buffers
// ════════════════════════════════════════════════════════════════════════════

void FramePool::init(int maxWidth, int maxHeight) {
    if (initialized_) {
        LOGW("FramePool already initialized, skipping");
        return;
    }

    for (int i = 0; i < POOL_SIZE; ++i) {
        // Pre-allocate RGB buffer — this is the ONLY allocation that happens
        slots_[i].rgb = cv::Mat(maxHeight, maxWidth, CV_8UC3);
        slots_[i].rgb.setTo(cv::Scalar(0, 0, 0));
        slots_[i].frameId = -1;
        slots_[i].timestampMs = 0;
        slots_[i].originalWidth = 0;
        slots_[i].originalHeight = 0;
        slots_[i].refCount.store(0, std::memory_order_relaxed);
        slots_[i].ready.store(false, std::memory_order_relaxed);
    }

    writeIdx_.store(0, std::memory_order_relaxed);
    writeCount_.store(0, std::memory_order_relaxed);
    initialized_ = true;

    size_t totalBytes = static_cast<size_t>(POOL_SIZE) * maxWidth * maxHeight * 3;
    LOGI("FramePool initialized: %d slots × %dx%d = %.1f MB",
         POOL_SIZE, maxWidth, maxHeight, totalBytes / (1024.0 * 1024.0));
}

// ════════════════════════════════════════════════════════════════════════════
// Producer: Acquire next writable slot
//
// Strategy: advance writeIdx, skip slots with active readers.
// In practice with 5 slots and 1-2 consumers, starvation is impossible.
// ════════════════════════════════════════════════════════════════════════════

FrameSlot* FramePool::acquireForWrite() {
    if (!initialized_) return nullptr;

    // Try up to POOL_SIZE slots to find one without active readers
    for (int attempt = 0; attempt < POOL_SIZE; ++attempt) {
        int idx = writeIdx_.load(std::memory_order_relaxed) % POOL_SIZE;

        FrameSlot& slot = slots_[idx];

        // Skip if slot is still being read by a consumer
        if (slot.refCount.load(std::memory_order_acquire) > 0) {
            writeIdx_.fetch_add(1, std::memory_order_relaxed);
            continue;
        }

        // Mark as not-ready while we write (consumers will skip)
        slot.ready.store(false, std::memory_order_release);

        // Advance write index for next call
        writeIdx_.fetch_add(1, std::memory_order_relaxed);
        writeCount_.fetch_add(1, std::memory_order_relaxed);

        return &slot;
    }

    // All slots busy — should never happen with 5 slots and 1-2 consumers
    LOGW("FramePool: all %d slots busy (slot starvation)", POOL_SIZE);
    return nullptr;
}

// ════════════════════════════════════════════════════════════════════════════
// Consumer: Acquire the most recent ready frame
//
// Scans backwards from writeIdx to find the latest ready slot.
// Increments refCount to prevent overwrite during use.
// ════════════════════════════════════════════════════════════════════════════

FrameSlot* FramePool::acquireLatest() {
    if (!initialized_) return nullptr;

    int currentWrite = writeIdx_.load(std::memory_order_acquire);

    // Scan backwards to find the most recent ready slot
    for (int i = 1; i <= POOL_SIZE; ++i) {
        int idx = ((currentWrite - i) % POOL_SIZE + POOL_SIZE) % POOL_SIZE;

        FrameSlot& slot = slots_[idx];

        if (slot.ready.load(std::memory_order_acquire)) {
            // Acquire reference — prevents producer from overwriting
            slot.acquire();
            return &slot;
        }
    }

    return nullptr; // No frame available yet
}

// ════════════════════════════════════════════════════════════════════════════
// Consumer: Acquire specific frame by ID (for historical access)
// ════════════════════════════════════════════════════════════════════════════

FrameSlot* FramePool::acquireByFrameId(int frameId) {
    if (!initialized_) return nullptr;

    for (int i = 0; i < POOL_SIZE; ++i) {
        FrameSlot& slot = slots_[i];

        if (slot.ready.load(std::memory_order_acquire) && slot.frameId == frameId) {
            slot.acquire();
            return &slot;
        }
    }

    return nullptr; // Frame expired or not found
}

// ════════════════════════════════════════════════════════════════════════════
// Release all resources
// ════════════════════════════════════════════════════════════════════════════

void FramePool::release() {
    if (!initialized_) return;

    for (int i = 0; i < POOL_SIZE; ++i) {
        slots_[i].rgb.release();
        slots_[i].ready.store(false, std::memory_order_relaxed);
        slots_[i].refCount.store(0, std::memory_order_relaxed);
        slots_[i].frameId = -1;
    }

    initialized_ = false;
    LOGI("FramePool released (%d frames written total)", writeCount_.load());
}

}