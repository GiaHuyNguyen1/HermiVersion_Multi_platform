cmake_minimum_required(VERSION 3.22.1)
project("hermivision" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ════════════════════════════════════════════════════════════════════════════
# OpenCV — from org.opencv:opencv:4.9.0 AAR via Prefab
# Prefab creates target: OpenCV::opencv_java4
# ════════════════════════════════════════════════════════════════════════════
find_package(OpenCV REQUIRED CONFIG)

# ════════════════════════════════════════════════════════════════════════════
# TFLite C API — Manual setup (AAR doesn't support Prefab)
#
# Headers: third_party/tflite/include/  (minimal C API declarations)
# Library: third_party/tflite/lib/arm64-v8a/libtensorflowlite_jni.so
#          (extracted from AAR by scripts/setup_native_deps.ps1)
# ════════════════════════════════════════════════════════════════════════════
set(TFLITE_DIR "${CMAKE_SOURCE_DIR}/third_party/tflite")

# Verify .so exists
set(TFLITE_LIB "${TFLITE_DIR}/lib/${ANDROID_ABI}/libtensorflowlite_jni.so")
if(NOT EXISTS "${TFLITE_LIB}")
    message(FATAL_ERROR
        "TFLite native library not found at: ${TFLITE_LIB}\n"
        "Run: powershell scripts/setup_native_deps.ps1\n"
        "This extracts the .so from the Gradle-cached TFLite AAR.")
endif()

# Create IMPORTED library target
add_library(tflite_jni SHARED IMPORTED)
set_target_properties(tflite_jni PROPERTIES
    IMPORTED_LOCATION "${TFLITE_LIB}"
)

# ════════════════════════════════════════════════════════════════════════════
# Optional: Delegate support
# ════════════════════════════════════════════════════════════════════════════
option(HERMIVISION_USE_NNAPI "Enable NNAPI delegate support" ON)
option(HERMIVISION_USE_GPU   "Enable GPU delegate support"   ON)

# GPU delegate library (extracted from tensorflow-lite-gpu AAR)
if(HERMIVISION_USE_GPU)
    set(TFLITE_GPU_LIB "${TFLITE_DIR}/lib/${ANDROID_ABI}/libtensorflowlite_gpu_jni.so")
    if(NOT EXISTS "${TFLITE_GPU_LIB}")
        message(WARNING "GPU .so not found at: ${TFLITE_GPU_LIB} — GPU delegate disabled")
        set(HERMIVISION_USE_GPU OFF)
    else()
        add_library(tflite_gpu_jni SHARED IMPORTED)
        set_target_properties(tflite_gpu_jni PROPERTIES
            IMPORTED_LOCATION "${TFLITE_GPU_LIB}"
        )
    endif()
endif()

# ════════════════════════════════════════════════════════════════════════════
# HermiVision Native Library
# ════════════════════════════════════════════════════════════════════════════
add_library(hermivision_native SHARED
    native_bridge.cpp
    engine/frame_pool.cpp
    engine/delegate_manager.cpp
    engine/preprocessor.cpp
    engine/hermivision_pipeline.cpp
    engine/pipeline_builder.cpp
    engine/tracker.cpp
    models/yolo_ball_detector.cpp
    models/court_detector.cpp
    engine/frame_pool.h
    engine/delegate_manager.h
    engine/hermivision_pipeline.h
    engine/pipeline_builder.h
    engine/interfaces/i_ai_model.h
    engine/interfaces/i_delegate_manager.h
    engine/preprocessor.h
    engine/tracker.h
    engine/types.h
    models/yolo_ball_detector.h
    models/court_detector.h
)

target_include_directories(hermivision_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${TFLITE_DIR}/include
)

if(HERMIVISION_USE_NNAPI)
    target_compile_definitions(hermivision_native PRIVATE HERMIVISION_USE_NNAPI)
endif()

if(HERMIVISION_USE_GPU)
    target_compile_definitions(hermivision_native PRIVATE HERMIVISION_USE_GPU)
endif()

# ════════════════════════════════════════════════════════════════════════════
# Linking — use exact Prefab target for OpenCV (carries include dirs)
# ════════════════════════════════════════════════════════════════════════════
target_link_libraries(hermivision_native
    OpenCV::opencv_java4    # Prefab target (includes + .so)
    tflite_jni              # Manual IMPORTED
    android
    log
    jnigraphics
)

# Link GPU delegate .so when enabled
if(HERMIVISION_USE_GPU)
    target_link_libraries(hermivision_native tflite_gpu_jni)
endif()

message(STATUS "HermiVision C++ Pipeline configured:")
message(STATUS "  OpenCV:  ${OpenCV_VERSION} (Prefab)")
message(STATUS "  TFLite:  manual (${TFLITE_LIB})")
message(STATUS "  NNAPI:   ${HERMIVISION_USE_NNAPI}")
message(STATUS "  GPU:     ${HERMIVISION_USE_GPU}")
message(STATUS "  ABI:     ${ANDROID_ABI}")