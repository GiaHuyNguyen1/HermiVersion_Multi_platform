#pragma once

#include "../types.h"
#include "../frame_pool.h"
#include <string>

namespace hermivision {

/**
 * Strategy Pattern: Abstract interface for all AI models.
 *
 * Concrete implementations:
 *   - YoloBallDetector (Phase 1 — existing)
 *   - CourtDetector    (Phase 3)
 *   - BounceDetector   (Future)
 *
 * Each model:
 *   1. Loads its TFLite/ONNX model file
 *   2. Configures hardware delegate (NNAPI→GPU→CPU fallback)
 *   3. Acquires frame from FramePool, processes, releases
 *
 * Process pattern (enforced by interface):
 *   1. Acquire frame from pool (pool.acquireLatest())
 *   2. Resize to model-specific input size
 *   3. Preprocess (normalize, etc.)
 *   4. TFLite invoke
 *   5. Postprocess → write results to ctx
 *   6. Release frame (slot->release())
 */
class IAIModel {
public:
    virtual ~IAIModel() = default;

    virtual std::string getActiveDelegate() const = 0;
    virtual bool loadModel(const std::string& modelPath, DelegateType delegate, int numThreads) = 0;
    virtual void process(FrameContext& ctx, FramePool& pool) = 0;
    virtual void release() = 0;
};

}