# HermiVision — Kotlin Multiplatform

AI-powered tennis analysis app rebuilt as a **Kotlin Multiplatform (KMP)** project.

## Project Modules

| Module | Description |
|--------|-------------|
| `:shared` | Pure KMP shared logic — domain models, interfaces, CourtHomography, AI config, SQLDelight DB schema. Targets: Android + iOS. |
| `:androidApp` | Android application — Compose UI, CameraX, JNI/NDK C++ engine (TFLite, ONNX, OpenCV), WorkManager. |
| `iosApp/` | iOS application — SwiftUI UI, AVFoundation camera, Core ML inference stub (Phase 2). |

## Architecture

```
:shared (commonMain)
├── domain/model      → BallFrame, CourtResult
├── domain/inference  → INativePipeline, AIConfig
├── domain/decoder    → IVideoDecoder, VideoMetadata
├── domain/court      → CourtHomography (pure math)
├── data              → ProcessingResultRepository
└── platform          → expect DeviceProfiler, BackgroundTaskScheduler

:androidApp
├── data/inference    → NativePipeline (JNI), DeviceProfiler (TFLite benchmark)
├── data/decoder      → VideoDecoder (MediaCodec)
├── data/worker       → VideoProcessingWorker (WorkManager)
├── domain/camera     → CameraAnalyzer (CameraX)
├── ui/               → Compose screens (Picker, Processing, Results, Live, Optimizing)
└── cpp/              → C++ engine (OpenCV, TFLite, ONNX, NEON)

iosApp/
├── camera/           → AVFoundationCameraController
├── inference/        → IosInferencePipeline (Core ML — Phase 2)
└── ui/               → SwiftUI screens (matching Android screens)
```

## Build

```bash
# Android
./gradlew :androidApp:assembleDebug

# Shared module (metadata)
./gradlew :shared:compileCommonMainKotlinMetadata

# iOS framework (requires macOS)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Key Design Decisions

- **SQLDelight** replaces Room — runs on both Android and iOS natively.
- **C++ NDK engine** stays in `:androidApp` only — iOS uses Core ML in Phase 2.
- **expect/actual** pattern used for `DeviceProfiler` and `BackgroundTaskScheduler`.
