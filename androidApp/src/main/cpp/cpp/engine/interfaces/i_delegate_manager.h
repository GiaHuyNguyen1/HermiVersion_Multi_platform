#pragma once

#include "tensorflow/lite/c/c_api.h"
#include "../types.h"
#include <string>

namespace hermivision {

/**
 * SOLID (Dependency Inversion): Abstract interface for TFLite delegate management.
 *
 * Decouples AI model classes (YoloBallDetector, CourtDetector) from the
 * concrete DelegateManager implementation, enabling:
 *   - Future testability (mock delegate in unit tests)
 *   - Swappable delegate strategies without modifying model code
 *
 * Fallback chain implemented by concrete class:
 *   NNAPI (NPU/DSP) → GPU (OpenCL/GL) → CPU (XNNPACK auto-enabled)
 */
class IDelegateManager {
public:
    virtual ~IDelegateManager() = default;

    /**
     * Create a TFLite interpreter with the best available delegate.
     * Tries the preferred delegate first, falls back automatically.
     *
     * @param model      Loaded TFLite model (caller retains ownership)
     * @param cacheDir   Directory for delegate compilation cache
     * @param preferred  Preferred delegate from DeviceProfiler benchmark
     * @param numThreads CPU thread count for fallback/CPU mode
     * @return Interpreter pointer, or nullptr on total failure
     */
    virtual TfLiteInterpreter* createInterpreter(TfLiteModel* model, const std::string& cacheDir, DelegateType preferred, int numThreads) = 0;
    virtual const std::string& getActiveDelegateName() const = 0;
    virtual TfLiteInterpreter* getInterpreter() const = 0;
    virtual void release() = 0;
};

}