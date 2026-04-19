import CoreVideo
import CoreImage

/// Phase 1 stub for iOS inference pipeline.
/// Will be replaced by Core ML / TFLite iOS integration in Phase 2.
final class IosInferencePipeline {
    
    struct InferenceResult {
        let ballVisible: Bool
        let ballRect: CGRect
    }
    
    func process(pixelBuffer: CVPixelBuffer) -> InferenceResult {
        // TODO Phase 2: integrate Core ML model or TFLite iOS
        // For Phase 1, return a stub result so the UI pipeline is exercised end-to-end
        return InferenceResult(ballVisible: false, ballRect: .zero)
    }
}
