#pragma once

#include "types.h"
#include "hermivision_pipeline.h"
#include <memory>
#include <string>

namespace hermivision {

/**
 * Builder Pattern: Fluent API for constructing HermiVisionPipeline.
 *
 * Responsibilities:
 *   - Validates required fields (ballModelPath must be set)
 *   - Provides sensible defaults (CPU delegate, 4 threads)
 *   - Produces a ready-to-use pipeline or nullptr on validation failure
 *
 * Usage (replaces manual PipelineConfig construction in native_bridge.cpp):
 *
 *   auto pipeline = PipelineBuilder()
 *       .withBallModel(ballPath, DelegateType::NNAPI, 4)
 *       .withCourtModel(courtPath, DelegateType::CPU)
 *       .build();
 *
 *   if (!pipeline) { return JNI_FALSE; }  // Validation failed
 */
class PipelineBuilder {
public:
    PipelineBuilder() = default;

    /// Set ball detection model path and its hardware delegate
    PipelineBuilder& withBallModel(const std::string& path, DelegateType delegate, int numThreads);

    /// Set court detection model path and its hardware delegate (optional)
    /// If not called, court detection is disabled (courtModelPath stays empty)
    PipelineBuilder& withCourtModel(const std::string& path, DelegateType delegate);

    /**
     * Validate configuration and build the pipeline.
     *
     * Validation rules:
     *   - ballModelPath must be non-empty
     *   - numThreads must be >= 1
     *
     * @return Initialized pipeline, or nullptr if validation fails or init() fails
     */
    std::unique_ptr<HermiVisionPipeline> build();

private:
    PipelineConfig config_;
    bool ballModelSet_ = false;
};

} // namespace hermivision
