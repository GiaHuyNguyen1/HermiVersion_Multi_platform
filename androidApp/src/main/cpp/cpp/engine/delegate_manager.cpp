#include "delegate_manager.h"

#include <android/log.h>

// Delegate-specific headers
// These are available via TFLite AAR Prefab or third_party includes
#ifdef HERMIVISION_USE_NNAPI
#include "tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h"
#endif

#ifdef HERMIVISION_USE_GPU
#include "tensorflow/lite/delegates/gpu/delegate.h"
#endif

// XNNPACK — always available (bundled in libtensorflowlite_jni.so)
// This is the HIGH-PERFORMANCE CPU backend using SIMD/NEON vectorization.
// Without explicit creation, TFLite C API falls back to SLOW reference kernels.
#include "tensorflow/lite/delegates/xnnpack/xnnpack_delegate.h"

#define LOG_TAG "DelegateManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hermivision {

DelegateManager::~DelegateManager() {
    release();
}

TfLiteInterpreter* DelegateManager::createInterpreter(
    TfLiteModel* model,
    const std::string& cacheDir,
    DelegateType preferred,
    int numThreads
) {
    // Release previous interpreter/delegate if any
    release();

    // Build fallback chain starting from preferred delegate
    // NNAPI → GPU → CPU
    std::vector<DelegateType> chain;
    switch (preferred) {
        case DelegateType::NNAPI:
            chain = {DelegateType::NNAPI, DelegateType::GPU, DelegateType::CPU};
            break;
        case DelegateType::GPU:
            chain = {DelegateType::GPU, DelegateType::CPU};
            break;
        case DelegateType::CPU:
        default:
            chain = {DelegateType::CPU};
            break;
    }

    for (auto type : chain) {
        TfLiteInterpreter* interp = tryCreate(model, cacheDir, type, numThreads);
        if (interp) {
            interpreter_ = interp;
            LOGI("✓ Interpreter created with %s", activeDelegateName_.c_str());
            return interp;
        }
    }

    // Should never reach here — CPU always works
    LOGE("✗ CRITICAL: All delegates failed, including CPU!");
    return nullptr;
}

TfLiteInterpreter* DelegateManager::tryCreate(
    TfLiteModel* model,
    const std::string& cacheDir,
    DelegateType type,
    int numThreads
) {
    TfLiteInterpreterOptions* options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(options, numThreads);

    TfLiteDelegate* delegate = nullptr;

    switch (type) {
        case DelegateType::NNAPI: {
#ifdef HERMIVISION_USE_NNAPI
            LOGI("Trying NNAPI delegate (NPU/DSP)...");
            TfLiteNnapiDelegateOptions nnapi_opts = TfLiteNnapiDelegateOptionsDefault();
            delegate = TfLiteNnapiDelegateCreate(&nnapi_opts);
            if (delegate) {
                TfLiteInterpreterOptionsAddDelegate(options, delegate);
            } else {
                LOGW("✗ NNAPI delegate creation failed");
                TfLiteInterpreterOptionsDelete(options);
                return nullptr;
            }
#else
            LOGW("✗ NNAPI support not compiled in (HERMIVISION_USE_NNAPI not defined)");
            TfLiteInterpreterOptionsDelete(options);
            return nullptr;
#endif
            break;
        }

        case DelegateType::GPU: {
#ifdef HERMIVISION_USE_GPU
            LOGI("Trying GPU delegate (OpenCL/OpenGL)...");
            TfLiteGpuDelegateOptionsV2 gpu_opts = TfLiteGpuDelegateOptionsV2Default();
            gpu_opts.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;
            gpu_opts.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
            
            if (!cacheDir.empty()) {
                gpu_opts.experimental_flags |= TFLITE_GPU_EXPERIMENTAL_FLAGS_ENABLE_SERIALIZATION;
                gpu_opts.serialization_dir = cacheDir.c_str();
                gpu_opts.model_token = "HermiVisionYoloBall"; // Must be unique per model graph
                LOGI("GPU Shader Serialization ENABLED — Cache dir: %s", cacheDir.c_str());
            } else {
                LOGW("No cache dir provided, GPU compilation will be extremely slow (~18s) every time!");
            }

            delegate = TfLiteGpuDelegateV2Create(&gpu_opts);
            if (delegate) {
                TfLiteInterpreterOptionsAddDelegate(options, delegate);
            } else {
                LOGW("✗ GPU delegate creation failed");
                TfLiteInterpreterOptionsDelete(options);
                return nullptr;
            }
#else
            LOGW("✗ GPU support not compiled in (HERMIVISION_USE_GPU not defined)");
            TfLiteInterpreterOptionsDelete(options);
            return nullptr;
#endif
            break;
        }

        case DelegateType::CPU: {
            // CRITICAL: TFLite C API does NOT auto-enable XNNPACK!
            // We must explicitly create and attach the XNNPACK delegate.
            // Without this, inference uses slow reference CPU kernels (~2x slower).
            LOGI("Creating XNNPACK delegate with %d threads...", numThreads);
            TfLiteXNNPackDelegateOptions xnnpack_opts = TfLiteXNNPackDelegateOptionsDefault();
            xnnpack_opts.num_threads = numThreads;
            delegate = TfLiteXNNPackDelegateCreate(&xnnpack_opts);
            if (delegate) {
                TfLiteInterpreterOptionsAddDelegate(options, delegate);
                LOGI("✓ XNNPACK delegate created (SIMD/NEON accelerated)");
            } else {
                LOGW("✗ XNNPACK delegate creation failed, falling back to reference CPU");
            }
            break;
        }
    }

    // Create interpreter
    TfLiteInterpreter* interp = TfLiteInterpreterCreate(model, options);
    TfLiteInterpreterOptionsDelete(options);

    if (!interp) {
        LOGW("✗ TfLiteInterpreterCreate failed for %s",
             type == DelegateType::NNAPI ? "NNAPI" :
             type == DelegateType::GPU   ? "GPU" : "CPU");
        if (delegate) deleteDelegate(delegate, type);
        return nullptr;
    }

    // Allocate tensors
    if (TfLiteInterpreterAllocateTensors(interp) != kTfLiteOk) {
        LOGW("✗ AllocateTensors failed for %s",
             type == DelegateType::NNAPI ? "NNAPI" :
             type == DelegateType::GPU   ? "GPU" : "CPU");
        TfLiteInterpreterDelete(interp);
        if (delegate) deleteDelegate(delegate, type);
        return nullptr;
    }

    // Success — store delegate for lifecycle management
    activeDelegate_ = delegate;
    activeDelegateType_ = type;
    switch (type) {
        case DelegateType::NNAPI:
            activeDelegateName_ = "NPU (NNAPI)";
            break;
        case DelegateType::GPU:
            activeDelegateName_ = "GPU";
            break;
        case DelegateType::CPU:
            activeDelegateName_ = "CPU/XNNPACK (" + std::to_string(numThreads) + " threads)";
            break;
    }

    return interp;
}

void DelegateManager::deleteDelegate(TfLiteDelegate* delegate, DelegateType type) {
    if (!delegate) return;

    switch (type) {
#ifdef HERMIVISION_USE_NNAPI
        case DelegateType::NNAPI:
            TfLiteNnapiDelegateDelete(delegate);
            break;
#endif
#ifdef HERMIVISION_USE_GPU
        case DelegateType::GPU:
            TfLiteGpuDelegateV2Delete(delegate);
            break;
#endif
        case DelegateType::CPU:
            // CPU path now uses explicit XNNPACK delegate — must free it
            TfLiteXNNPackDelegateDelete(delegate);
            break;
        default:
            break;
    }
}

void DelegateManager::release() {
    if (interpreter_) {
        TfLiteInterpreterDelete(interpreter_);
        interpreter_ = nullptr;
    }
    if (activeDelegate_) {
        deleteDelegate(activeDelegate_, activeDelegateType_);
        activeDelegate_ = nullptr;
    }
    activeDelegateName_ = "none";
}

}