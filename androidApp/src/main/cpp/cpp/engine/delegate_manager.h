#pragma once

#include "types.h"
#include "interfaces/i_delegate_manager.h"
#include "tensorflow/lite/c/c_api.h"

namespace hermivision {

/**
 * Concrete implementation of IDelegateManager.
 *
 * Manages TFLite hardware delegate lifecycle with auto-fallback.
 * Fallback chain: NNAPI (NPU/DSP) → GPU (OpenCL/GL) → CPU (XNNPACK auto-enabled)
 *
 * Usage:
 *   DelegateManager dm;
 *   TfLiteInterpreter* interp = dm.createInterpreter(model, cacheDir, DelegateType::NNAPI, 4);
 *   // ... run inference ...
 *   dm.release();  // Cleans up delegate + interpreter
 */
class DelegateManager : public IDelegateManager {
public:
    DelegateManager() = default;
    ~DelegateManager() override;

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
    TfLiteInterpreter* createInterpreter(TfLiteModel* model, const std::string& cacheDir, DelegateType preferred, int numThreads) override;
    TfLiteInterpreter* getInterpreter() const override { return interpreter_; }

    const std::string& getActiveDelegateName() const override { return activeDelegateName_; }
    void release() override;

private:
    TfLiteInterpreter* interpreter_ = nullptr;
    TfLiteDelegate* activeDelegate_ = nullptr;
    DelegateType activeDelegateType_ = DelegateType::CPU;
    std::string activeDelegateName_ = "none";

    TfLiteInterpreter* tryCreate(TfLiteModel* model, const std::string& cacheDir, DelegateType type, int numThreads);

    static void deleteDelegate(TfLiteDelegate* delegate, DelegateType type);
};

}