#include "pipeline_builder.h"

#include <android/log.h>

#define LOG_TAG "PipelineBuilder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

PipelineBuilder& PipelineBuilder::withBallModel(
    const std::string& path, DelegateType delegate, int numThreads)
{
    config_.ballModelPath = path;
    config_.delegate      = delegate;
    config_.numThreads    = numThreads;
    ballModelSet_         = true;
    return *this;
}

PipelineBuilder& PipelineBuilder::withCourtModel(
    const std::string& path, DelegateType delegate)
{
    config_.courtModelPath = path;
    config_.courtDelegate  = delegate;
    return *this;
}

std::unique_ptr<HermiVisionPipeline> PipelineBuilder::build() {
    // ── Validation ──
    if (!ballModelSet_ || config_.ballModelPath.empty()) {
        LOGE("build() failed: ballModelPath is required");
        return nullptr;
    }
    if (config_.numThreads < 1) {
        LOGE("build() failed: numThreads must be >= 1 (got %d)", config_.numThreads);
        return nullptr;
    }

    LOGI("Building pipeline: ball=%s delegate=%d threads=%d court=%s",
         config_.ballModelPath.c_str(),
         static_cast<int>(config_.delegate),
         config_.numThreads,
         config_.courtModelPath.empty() ? "(disabled)" : config_.courtModelPath.c_str());

    // ── Construct + init ──
    auto pipeline = std::make_unique<HermiVisionPipeline>();
    if (!pipeline->init(config_)) {
        LOGE("build() failed: pipeline->init() returned false");
        return nullptr;
    }

    return pipeline;
}

} // namespace hermivision
