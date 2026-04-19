import CoreGraphics
import CoreML
import CoreVideo
import Foundation
import ImageIO
import Vision

final class IosInferencePipeline {

    struct LiveInferenceResult: Equatable {
        let ballVisible: Bool
        let ballRect: CGRect
        let ballScore: Float
        let courtValid: Bool
        let courtKeypoints: [CGPoint]
        let runtimeLabel: String
    }

    enum Availability: Equatable {
        case available(runtimeLabel: String, message: String?)
        case unavailable(runtimeLabel: String, message: String)

        var runtimeLabel: String {
            switch self {
            case let .available(runtimeLabel, _), let .unavailable(runtimeLabel, _):
                return runtimeLabel
            }
        }

        var message: String? {
            switch self {
            case let .available(_, message):
                return message
            case let .unavailable(_, message):
                return message
            }
        }
    }

    private struct ModelConfig {
        let baseNames: [String]
        let inputSize: CGSize
        let cropOption: VNImageCropAndScaleOption
    }

    private final class VisionModelSession {
        let request: VNCoreMLRequest
        let inputSize: CGSize

        init(modelURL: URL, inputSize: CGSize, cropOption: VNImageCropAndScaleOption) throws {
            let model = try Self.loadModel(from: modelURL)
            let vnModel = try VNCoreMLModel(for: model)
            let request = VNCoreMLRequest(model: vnModel)
            request.imageCropAndScaleOption = cropOption
            self.request = request
            self.inputSize = inputSize
        }

        deinit {
            request.cancel()
        }

        func perform(on pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) throws -> [VNObservation] {
            let handler = VNImageRequestHandler(
                cvPixelBuffer: pixelBuffer,
                orientation: orientation,
                options: [:]
            )
            try handler.perform([request])
            return request.results ?? []
        }

        private static func loadModel(from url: URL) throws -> MLModel {
            let configuration = MLModelConfiguration()
            configuration.computeUnits = .all

            if url.pathExtension.lowercased() == "mlmodelc" {
                return try MLModel(contentsOf: url, configuration: configuration)
            }

            let compiledURL = try MLModel.compileModel(at: url)
            return try MLModel(contentsOf: compiledURL, configuration: configuration)
        }
    }

    private enum PipelineError: LocalizedError {
        case missingResource(String)
        case loadFailed(String)

        var errorDescription: String? {
            switch self {
            case let .missingResource(message), let .loadFailed(message):
                return message
            }
        }
    }

    private let ballConfig = ModelConfig(
        baseNames: ["YoloBall-nano-FP32", "YoloBall-nano-FP16", "YoloBall-small-FP32"],
        inputSize: CGSize(width: 640, height: 640),
        cropOption: .scaleFit
    )
    private let courtConfig = ModelConfig(
        baseNames: ["Court-Keypoint-FP32", "Court-Keypoint-FP16"],
        inputSize: CGSize(width: 480, height: 480),
        cropOption: .scaleFill
    )

    private var ballSession: VisionModelSession?
    private var courtSession: VisionModelSession?

    private(set) var availability: Availability = .unavailable(
        runtimeLabel: "Preview",
        message: "CoreML models are not loaded."
    )

    func loadIfNeeded() -> Availability {
        if case .available = availability, ballSession != nil {
            return availability
        }

        do {
            let ballModelURL = try Self.findBundledModelURL(for: ballConfig.baseNames)

            ballSession = try VisionModelSession(
                modelURL: ballModelURL,
                inputSize: ballConfig.inputSize,
                cropOption: ballConfig.cropOption
            )

            do {
                let courtModelURL = try Self.findBundledModelURL(for: courtConfig.baseNames)
                courtSession = try VisionModelSession(
                    modelURL: courtModelURL,
                    inputSize: courtConfig.inputSize,
                    cropOption: courtConfig.cropOption
                )
                availability = .available(runtimeLabel: "CoreML", message: nil)
            } catch {
                courtSession = nil
                availability = .available(
                    runtimeLabel: "CoreML",
                    message: "Court model is not bundled yet. Ball detection is active; court detection is disabled."
                )
            }
        } catch {
            ballSession = nil
            courtSession = nil
            availability = .unavailable(
                runtimeLabel: "Preview",
                message: error.localizedDescription
            )
        }

        return availability
    }

    func release() {
        ballSession = nil
        courtSession = nil
        availability = .unavailable(
            runtimeLabel: "Preview",
            message: "CoreML models are not loaded."
        )
    }

    func process(
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation = .right
    ) -> LiveInferenceResult? {
        guard case let .available(runtimeLabel, _) = availability,
              let loadedBallSession = ballSession
        else {
            return nil
        }

        let imageSize = CGSize(
            width: CVPixelBufferGetWidth(pixelBuffer),
            height: CVPixelBufferGetHeight(pixelBuffer)
        )

        do {
            let ballObservations = try loadedBallSession.perform(on: pixelBuffer, orientation: orientation)
            let courtObservations = try courtSession?.perform(on: pixelBuffer, orientation: orientation) ?? []

            let ballResult = parseBallResult(
                from: ballObservations,
                imageSize: imageSize,
                inputSize: loadedBallSession.inputSize
            )
            let courtKeypoints = parseCourtKeypoints(from: courtObservations)

            return LiveInferenceResult(
                ballVisible: ballResult.visible,
                ballRect: ballResult.rect,
                ballScore: ballResult.score,
                courtValid: !courtKeypoints.isEmpty,
                courtKeypoints: courtKeypoints,
                runtimeLabel: runtimeLabel
            )
        } catch {
            availability = .unavailable(
                runtimeLabel: "Preview",
                message: "CoreML inference failed: \(error.localizedDescription)"
            )
            ballSession = nil
            courtSession = nil
            return nil
        }
    }

    private func parseBallResult(
        from observations: [VNObservation],
        imageSize: CGSize,
        inputSize: CGSize
    ) -> (visible: Bool, rect: CGRect, score: Float) {
        if let recognized = observations as? [VNRecognizedObjectObservation],
           let best = recognized.max(by: { ($0.labels.first?.confidence ?? 0) < ($1.labels.first?.confidence ?? 0) }),
           let label = best.labels.first {
            let box = CGRect(
                x: best.boundingBox.minX,
                y: 1 - best.boundingBox.maxY,
                width: best.boundingBox.width,
                height: best.boundingBox.height
            )
            return (label.confidence > 0.25, Self.clampRect(box), label.confidence)
        }

        guard let featureValue = observations
            .compactMap({ $0 as? VNCoreMLFeatureValueObservation })
            .first?.featureValue.multiArrayValue
        else {
            return (false, .zero, 0)
        }

        return parseBallMultiArray(
            featureValue,
            imageSize: imageSize,
            inputSize: inputSize
        )
    }

    private func parseBallMultiArray(
        _ multiArray: MLMultiArray,
        imageSize: CGSize,
        inputSize: CGSize
    ) -> (visible: Bool, rect: CGRect, score: Float) {
        let shape = multiArray.shape.map(\.intValue)
        let strides = multiArray.strides.map(\.intValue)

        guard shape.count == 3 else {
            return (false, .zero, 0)
        }

        if shape[1] == 5 {
            let anchorCount = shape[2]
            var bestScore: Float = 0
            var bestRect = CGRect.zero

            for anchor in 0..<anchorCount {
                let score = floatValue(
                    in: multiArray,
                    offset: 4 * strides[1] + anchor * strides[2]
                )
                guard score > bestScore else { continue }

                let centerX = floatValue(
                    in: multiArray,
                    offset: anchor * strides[2]
                ) * Float(inputSize.width)
                let centerY = floatValue(
                    in: multiArray,
                    offset: strides[1] + anchor * strides[2]
                ) * Float(inputSize.height)
                let width = floatValue(
                    in: multiArray,
                    offset: 2 * strides[1] + anchor * strides[2]
                ) * Float(inputSize.width)
                let height = floatValue(
                    in: multiArray,
                    offset: 3 * strides[1] + anchor * strides[2]
                ) * Float(inputSize.height)

                bestScore = score
                bestRect = Self.unletterboxRect(
                    centerX: centerX,
                    centerY: centerY,
                    width: width,
                    height: height,
                    imageSize: imageSize,
                    modelInputSize: inputSize
                )
            }

            return (bestScore > 0.25, bestRect, bestScore)
        }

        if shape[2] >= 5 {
            let detectionCount = shape[1]
            var bestScore: Float = 0
            var bestRect = CGRect.zero

            for detectionIndex in 0..<detectionCount {
                let base = detectionIndex * strides[1]
                let score = floatValue(
                    in: multiArray,
                    offset: base + 4 * strides[2]
                )
                guard score > bestScore else { continue }

                let x1 = floatValue(in: multiArray, offset: base)
                let y1 = floatValue(in: multiArray, offset: base + strides[2])
                let x2 = floatValue(in: multiArray, offset: base + 2 * strides[2])
                let y2 = floatValue(in: multiArray, offset: base + 3 * strides[2])

                bestScore = score
                bestRect = Self.clampRect(
                    CGRect(
                        x: CGFloat(x1) / inputSize.width,
                        y: CGFloat(y1) / inputSize.height,
                        width: CGFloat(x2 - x1) / inputSize.width,
                        height: CGFloat(y2 - y1) / inputSize.height
                    )
                )
            }

            return (bestScore > 0.25, bestRect, bestScore)
        }

        return (false, .zero, 0)
    }

    private func parseCourtKeypoints(from observations: [VNObservation]) -> [CGPoint] {
        guard let featureValue = observations
            .compactMap({ $0 as? VNCoreMLFeatureValueObservation })
            .first?.featureValue.multiArrayValue
        else {
            return []
        }

        let totalCount = featureValue.count
        guard totalCount >= 28 else { return [] }

        var points: [CGPoint] = []
        points.reserveCapacity(14)

        for index in stride(from: 0, to: 28, by: 2) {
            let x = CGFloat(floatValue(in: featureValue, offset: index))
            let y = CGFloat(floatValue(in: featureValue, offset: index + 1))
            points.append(
                CGPoint(
                    x: min(max(x, 0), 1),
                    y: min(max(y, 0), 1)
                )
            )
        }

        return points
    }

    private static func findBundledModelURL(for baseNames: [String]) throws -> URL {
        guard let resourceURL = Bundle.main.resourceURL else {
            throw PipelineError.loadFailed("Unable to locate app bundle resources.")
        }

        let fileManager = FileManager.default
        let supportedExtensions = ["mlmodelc", "mlpackage", "mlmodel"]

        for baseName in baseNames {
            for supportedExtension in supportedExtensions {
                if let bundleURL = Bundle.main.url(forResource: baseName, withExtension: supportedExtension) {
                    return bundleURL
                }

                let fallbackURL = resourceURL.appendingPathComponent("\(baseName).\(supportedExtension)")
                if fileManager.fileExists(atPath: fallbackURL.path) {
                    return fallbackURL
                }
            }
        }

        let expectedNames = baseNames.joined(separator: ", ")
        throw PipelineError.missingResource(
            "CoreML models are not bundled. Add \(expectedNames) as .mlmodel/.mlpackage to the iosApp target."
        )
    }

    private static func unletterboxRect(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        imageSize: CGSize,
        modelInputSize: CGSize
    ) -> CGRect {
        let scale = min(
            modelInputSize.width / imageSize.width,
            modelInputSize.height / imageSize.height
        )
        let scaledWidth = imageSize.width * scale
        let scaledHeight = imageSize.height * scale
        let padX = (modelInputSize.width - scaledWidth) / 2
        let padY = (modelInputSize.height - scaledHeight) / 2

        let x = (CGFloat(centerX) - CGFloat(width) / 2 - padX) / scale
        let y = (CGFloat(centerY) - CGFloat(height) / 2 - padY) / scale
        let w = CGFloat(width) / scale
        let h = CGFloat(height) / scale

        return clampRect(
            CGRect(
                x: x / imageSize.width,
                y: y / imageSize.height,
                width: w / imageSize.width,
                height: h / imageSize.height
            )
        )
    }

    private static func clampRect(_ rect: CGRect) -> CGRect {
        let normalized = CGRect(
            x: min(max(rect.origin.x, 0), 1),
            y: min(max(rect.origin.y, 0), 1),
            width: max(0, rect.size.width),
            height: max(0, rect.size.height)
        )

        let maxWidth = max(0, 1 - normalized.minX)
        let maxHeight = max(0, 1 - normalized.minY)

        return CGRect(
            x: normalized.minX,
            y: normalized.minY,
            width: min(normalized.width, maxWidth),
            height: min(normalized.height, maxHeight)
        )
    }

    private func floatValue(in multiArray: MLMultiArray, offset: Int) -> Float {
        switch multiArray.dataType {
        case .double:
            return Float(multiArray.dataPointer.assumingMemoryBound(to: Double.self)[offset])
        case .float16:
            return Float(multiArray.dataPointer.assumingMemoryBound(to: Float16.self)[offset])
        case .float32:
            return multiArray.dataPointer.assumingMemoryBound(to: Float.self)[offset]
        case .int32:
            return Float(multiArray.dataPointer.assumingMemoryBound(to: Int32.self)[offset])
        default:
            return 0
        }
    }
}
