import CoreGraphics
import CoreImage
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
        let courtFrameSize: CGSize
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

    private struct CourtValidationSummary {
        let valid: Bool
        let reason: String
        let supportedLines: Int
        let supportedOuterLines: Int
        let supportedInnerLines: Int
        let averageSupport: Double
        let lineSupports: [Double]
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

    private final class CourtModelSession {
        let model: MLModel
        let inputSize: Int

        init(modelURL: URL, inputSize: Int) throws {
            self.model = try Self.loadModel(from: modelURL)
            self.inputSize = inputSize
        }

        func perform(input: MLMultiArray) throws -> MLMultiArray? {
            let provider = try MLDictionaryFeatureProvider(dictionary: [
                "input": MLFeatureValue(multiArray: input)
            ])
            let output = try model.prediction(from: provider)
            return output.featureValue(for: "keypoints")?.multiArrayValue
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
    private var courtSession: CourtModelSession?
    private let courtImageContext = CIContext()
    private let courtDetectionInterval = 30
    private var processedFrameCount = 0
    private var cachedCourtKeypoints: [CGPoint] = []
    private var courtInvalidIntervalCount = 0
    private let courtSearchInterval = 5
    private let courtLineSupportThreshold = 0.22
    private let courtSmoothingAlpha: CGFloat = 0.35
    private let courtMaximumPointStep: CGFloat = 0.055
    private let courtLineSnapSearchRadius: CGFloat = 18
    private let courtLineSnapStep: CGFloat = 2
    private let courtLineSegments = [
        (0, 1), (2, 3), (0, 2), (1, 3),
        (4, 8), (8, 10), (10, 5),
        (6, 9), (9, 11), (11, 7),
        (8, 9), (10, 11), (12, 13)
    ]

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
                courtSession = try CourtModelSession(
                    modelURL: courtModelURL,
                    inputSize: Int(courtConfig.inputSize.width)
                )
                availability = .available(runtimeLabel: "CoreML-MA", message: nil)
            } catch {
                courtSession = nil
                availability = .available(
                    runtimeLabel: "CoreML-MA",
                    message: "Court detection is unavailable. Ball detection remains active."
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
        processedFrameCount = 0
        cachedCourtKeypoints = []
        courtInvalidIntervalCount = 0
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
            processedFrameCount += 1

            let ballResult = parseBallResult(
                from: ballObservations,
                imageSize: imageSize,
                inputSize: loadedBallSession.inputSize
            )
            let courtKeypoints = processCourtIfNeeded(
                pixelBuffer: pixelBuffer,
                orientation: orientation
            )

            return LiveInferenceResult(
                ballVisible: ballResult.visible,
                ballRect: ballResult.rect,
                ballScore: ballResult.score,
                courtValid: !courtKeypoints.isEmpty,
                courtKeypoints: courtKeypoints,
                courtFrameSize: imageSize,
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

    private func processCourtIfNeeded(
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation
    ) -> [CGPoint] {
        guard let loadedCourtSession = courtSession else {
            cachedCourtKeypoints = []
            return []
        }

        let activeInterval = cachedCourtKeypoints.isEmpty ? courtSearchInterval : courtDetectionInterval
        let shouldRunCourt = processedFrameCount % activeInterval == 0
        guard shouldRunCourt else {
            return cachedCourtKeypoints
        }

        do {
            let input = try makeCourtInput(from: pixelBuffer, inputSize: loadedCourtSession.inputSize)
            let rawKeypoints = parseCourtKeypoints(from: try loadedCourtSession.perform(input: input))
            let keypoints = refineCourtKeypoints(
                rawKeypoints,
                pixelBuffer: pixelBuffer,
                orientation: .up
            )
            let validation = validateCourtPaintedLines(
                keypoints: keypoints,
                pixelBuffer: pixelBuffer,
                orientation: .up
            )
            let shouldAcceptCourt = validation.valid || shouldAcceptCourtTrackingUpdate(
                keypoints: keypoints,
                validation: validation
            )
            if shouldAcceptCourt {
                cachedCourtKeypoints = smoothCourtKeypoints(keypoints)
                courtInvalidIntervalCount = 0
            } else if !cachedCourtKeypoints.isEmpty {
                courtInvalidIntervalCount += 1
                if courtInvalidIntervalCount >= 5 {
                    cachedCourtKeypoints = []
                }
            }
            return cachedCourtKeypoints
        } catch {
            courtSession = nil
            cachedCourtKeypoints = []
            availability = .available(
                runtimeLabel: "CoreML-MA",
                message: "Court inference failed: \(error.localizedDescription)"
            )
            return []
        }
    }

    private func smoothCourtKeypoints(_ keypoints: [CGPoint]) -> [CGPoint] {
        guard cachedCourtKeypoints.count == keypoints.count else {
            return keypoints
        }

        return zip(cachedCourtKeypoints, keypoints).map { previous, current in
            let smoothed = CGPoint(
                x: previous.x + (current.x - previous.x) * courtSmoothingAlpha,
                y: previous.y + (current.y - previous.y) * courtSmoothingAlpha
            )

            return CGPoint(
                x: previous.x + min(max(smoothed.x - previous.x, -courtMaximumPointStep), courtMaximumPointStep),
                y: previous.y + min(max(smoothed.y - previous.y, -courtMaximumPointStep), courtMaximumPointStep)
            )
        }
    }

    private func shouldAcceptCourtTrackingUpdate(
        keypoints: [CGPoint],
        validation: CourtValidationSummary
    ) -> Bool {
        guard cachedCourtKeypoints.count == keypoints.count,
              keypoints.count == 14,
              validation.supportedLines >= 5,
              validation.supportedOuterLines >= 1,
              validation.supportedInnerLines >= 4,
              validation.averageSupport >= 0.20
        else {
            return false
        }

        let averageMotion = zip(cachedCourtKeypoints, keypoints)
            .map { previous, current in hypot(current.x - previous.x, current.y - previous.y) }
            .reduce(0, +) / CGFloat(keypoints.count)
        let maximumMotion = zip(cachedCourtKeypoints, keypoints)
            .map { previous, current in hypot(current.x - previous.x, current.y - previous.y) }
            .max() ?? 0

        return averageMotion <= 0.16 && maximumMotion <= 0.28
    }

    private func refineCourtKeypoints(
        _ keypoints: [CGPoint],
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation
    ) -> [CGPoint] {
        guard keypoints.count == 14 else {
            return keypoints
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let planeIndex = CVPixelBufferGetPlaneCount(pixelBuffer) > 0 ? 0 : -1
        let width = planeIndex >= 0 ? CVPixelBufferGetWidthOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetWidth(pixelBuffer)
        let height = planeIndex >= 0 ? CVPixelBufferGetHeightOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = planeIndex >= 0 ? CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetBytesPerRow(pixelBuffer)
        let baseAddress = planeIndex >= 0 ? CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetBaseAddress(pixelBuffer)

        guard let baseAddress else {
            return keypoints
        }

        let luminance = baseAddress.assumingMemoryBound(to: UInt8.self)
        let lines = courtLineSegments
        var accumulated = Array(repeating: CGPoint.zero, count: keypoints.count)
        var weights = Array(repeating: CGFloat(0), count: keypoints.count)

        for line in lines {
            let start = keypoints[line.0]
            let end = keypoints[line.1]
            let startPoint = mapNormalizedPoint(start, orientation: orientation, width: width, height: height)
            let endPoint = mapNormalizedPoint(end, orientation: orientation, width: width, height: height)
            let dx = endPoint.x - startPoint.x
            let dy = endPoint.y - startPoint.y
            let length = hypot(dx, dy)
            guard length > 24 else {
                continue
            }

            let normalX = -dy / length
            let normalY = dx / length
            let centerSupport = paintedLineSupport(
                from: start,
                to: end,
                orientation: orientation,
                width: width,
                height: height,
                bytesPerRow: bytesPerRow,
                luminance: luminance
            )
            var bestSupport = centerSupport
            var bestOffset: CGFloat = 0
            var offset = -courtLineSnapSearchRadius

            while offset <= courtLineSnapSearchRadius {
                let support = paintedLineSupport(
                    from: start,
                    to: end,
                    orientation: orientation,
                    width: width,
                    height: height,
                    bytesPerRow: bytesPerRow,
                    luminance: luminance,
                    normalOffset: offset
                )
                if support > bestSupport {
                    bestSupport = support
                    bestOffset = offset
                }
                offset += courtLineSnapStep
            }

            guard bestSupport >= 0.18,
                  bestSupport >= centerSupport + 0.08,
                  abs(bestOffset) > 0.5
            else {
                continue
            }

            let delta = normalizedDeltaForLineSnap(
                normalX: normalX,
                normalY: normalY,
                offset: bestOffset,
                orientation: orientation,
                width: width,
                height: height
            )
            let weight = CGFloat(bestSupport)

            for index in [line.0, line.1] {
                accumulated[index].x += (keypoints[index].x + delta.x) * weight
                accumulated[index].y += (keypoints[index].y + delta.y) * weight
                weights[index] += weight
            }
        }

        return keypoints.enumerated().map { index, point in
            guard weights[index] > 0 else {
                return point
            }

            let snapped = CGPoint(
                x: accumulated[index].x / weights[index],
                y: accumulated[index].y / weights[index]
            )

            return CGPoint(
                x: min(max(point.x * 0.35 + snapped.x * 0.65, 0), 1),
                y: min(max(point.y * 0.35 + snapped.y * 0.65, 0), 1)
            )
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

    private func parseCourtKeypoints(from featureValue: MLMultiArray?) -> [CGPoint] {
        guard let featureValue else {
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

    private func makeCourtInput(from pixelBuffer: CVPixelBuffer, inputSize: Int) throws -> MLMultiArray {
        let input = try MLMultiArray(
            shape: [
                NSNumber(value: 1),
                NSNumber(value: 3),
                NSNumber(value: inputSize),
                NSNumber(value: inputSize)
            ],
            dataType: .float32
        )
        let sourceWidth = CVPixelBufferGetWidth(pixelBuffer)
        let sourceHeight = CVPixelBufferGetHeight(pixelBuffer)
        let scale = CGAffineTransform(
            scaleX: CGFloat(inputSize) / CGFloat(sourceWidth),
            y: CGFloat(inputSize) / CGFloat(sourceHeight)
        )
        let image = CIImage(cvPixelBuffer: pixelBuffer).transformed(by: scale)
        var rgba = [UInt8](repeating: 0, count: inputSize * inputSize * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        rgba.withUnsafeMutableBytes { buffer in
            guard let baseAddress = buffer.baseAddress else { return }
            courtImageContext.render(
                image,
                toBitmap: baseAddress,
                rowBytes: inputSize * 4,
                bounds: CGRect(x: 0, y: 0, width: inputSize, height: inputSize),
                format: .RGBA8,
                colorSpace: colorSpace
            )
        }

        let values = input.dataPointer.assumingMemoryBound(to: Float.self)
        let planeSize = inputSize * inputSize
        for pixelIndex in 0..<planeSize {
            let sourceIndex = pixelIndex * 4
            values[pixelIndex] = Float(rgba[sourceIndex]) / 255.0
            values[planeSize + pixelIndex] = Float(rgba[sourceIndex + 1]) / 255.0
            values[2 * planeSize + pixelIndex] = Float(rgba[sourceIndex + 2]) / 255.0
        }

        return input
    }

    private func validateCourtPaintedLines(
        keypoints: [CGPoint],
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation
    ) -> CourtValidationSummary {
        guard keypoints.count == 14 else {
            return CourtValidationSummary(
                valid: false,
                reason: "expected 14 keypoints, got \(keypoints.count)",
                supportedLines: 0,
                supportedOuterLines: 0,
                supportedInnerLines: 0,
                averageSupport: 0,
                lineSupports: []
            )
        }

        let xs = keypoints.map(\.x)
        let ys = keypoints.map(\.y)
        guard let minX = xs.min(), let maxX = xs.max(),
              let minY = ys.min(), let maxY = ys.max(),
              maxX - minX > 0.18,
              maxY - minY > 0.25
        else {
            let spanX = (xs.max() ?? 0) - (xs.min() ?? 0)
            let spanY = (ys.max() ?? 0) - (ys.min() ?? 0)
            return CourtValidationSummary(
                valid: false,
                reason: String(format: "bbox too small span=(%.3f,%.3f)", spanX, spanY),
                supportedLines: 0,
                supportedOuterLines: 0,
                supportedInnerLines: 0,
                averageSupport: 0,
                lineSupports: []
            )
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let planeIndex = CVPixelBufferGetPlaneCount(pixelBuffer) > 0 ? 0 : -1
        let width = planeIndex >= 0 ? CVPixelBufferGetWidthOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetWidth(pixelBuffer)
        let height = planeIndex >= 0 ? CVPixelBufferGetHeightOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = planeIndex >= 0 ? CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetBytesPerRow(pixelBuffer)
        let baseAddress = planeIndex >= 0 ? CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, planeIndex) : CVPixelBufferGetBaseAddress(pixelBuffer)

        guard let baseAddress else {
            return CourtValidationSummary(
                valid: false,
                reason: "missing pixel buffer base address",
                supportedLines: 0,
                supportedOuterLines: 0,
                supportedInnerLines: 0,
                averageSupport: 0,
                lineSupports: []
            )
        }
        let luminance = baseAddress.assumingMemoryBound(to: UInt8.self)
        let lines = courtLineSegments

        var supportedLines = 0
        var supportedOuterLines = 0
        var supportedInnerLines = 0
        var totalSupport = 0.0
        var lineSupports: [Double] = []
        lineSupports.reserveCapacity(lines.count)
        for (index, line) in lines.enumerated() {
            let support = paintedLineSupport(
                from: keypoints[line.0],
                to: keypoints[line.1],
                orientation: orientation,
                width: width,
                height: height,
                bytesPerRow: bytesPerRow,
                luminance: luminance
            )
            totalSupport += support
            lineSupports.append(support)
            if support >= courtLineSupportThreshold {
                supportedLines += 1
                if index < 4 {
                    supportedOuterLines += 1
                } else {
                    supportedInnerLines += 1
                }
            }
        }

        let averageSupport = totalSupport / Double(lines.count)
        let valid = (
            supportedLines >= 5 &&
            supportedOuterLines >= 2 &&
            supportedInnerLines >= 3 &&
            averageSupport >= 0.18
        ) || (
            supportedLines >= 6 &&
            supportedOuterLines >= 1 &&
            supportedInnerLines >= 5 &&
            averageSupport >= 0.24
        )

        return CourtValidationSummary(
            valid: valid,
            reason: valid ? "pass painted-line gate" : "failed painted-line gate",
            supportedLines: supportedLines,
            supportedOuterLines: supportedOuterLines,
            supportedInnerLines: supportedInnerLines,
            averageSupport: averageSupport,
            lineSupports: lineSupports
        )
    }

    private func paintedLineSupport(
        from start: CGPoint,
        to end: CGPoint,
        orientation: CGImagePropertyOrientation,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        luminance: UnsafePointer<UInt8>,
        normalOffset: CGFloat = 0
    ) -> Double {
        let startPoint = mapNormalizedPoint(start, orientation: orientation, width: width, height: height)
        let endPoint = mapNormalizedPoint(end, orientation: orientation, width: width, height: height)
        let dx = endPoint.x - startPoint.x
        let dy = endPoint.y - startPoint.y
        let length = hypot(dx, dy)
        guard length > 24 else { return 0 }

        let samples = min(48, max(16, Int(length / 16)))
        var hits = 0
        for sampleIndex in 0..<samples {
            let t = Double(sampleIndex + 1) / Double(samples + 1)
            let x = startPoint.x + dx * t + (-dy / length) * normalOffset
            let y = startPoint.y + dy * t + (dx / length) * normalOffset
            if isPaintedLinePixel(
                x: x,
                y: y,
                normalX: -dy / length,
                normalY: dx / length,
                width: width,
                height: height,
                bytesPerRow: bytesPerRow,
                luminance: luminance
            ) {
                hits += 1
            }
        }

        return Double(hits) / Double(samples)
    }

    private func isPaintedLinePixel(
        x: CGFloat,
        y: CGFloat,
        normalX: CGFloat,
        normalY: CGFloat,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        luminance: UnsafePointer<UInt8>
    ) -> Bool {
        let sideDistance: CGFloat = 8
        let contrastThreshold = 22
        let sideSimilarityThreshold = 28
        let minimumLineLuminance = 95

        for offset in -5...5 {
            let centerX = x + normalX * CGFloat(offset)
            let centerY = y + normalY * CGFloat(offset)
            let leftX = centerX + normalX * sideDistance
            let leftY = centerY + normalY * sideDistance
            let rightX = centerX - normalX * sideDistance
            let rightY = centerY - normalY * sideDistance

            guard let center = luminanceValue(x: centerX, y: centerY, width: width, height: height, bytesPerRow: bytesPerRow, luminance: luminance),
                  let left = luminanceValue(x: leftX, y: leftY, width: width, height: height, bytesPerRow: bytesPerRow, luminance: luminance),
                  let right = luminanceValue(x: rightX, y: rightY, width: width, height: height, bytesPerRow: bytesPerRow, luminance: luminance)
            else {
                continue
            }

            let sideAverage = (left + right) / 2
            let centerContrast = center - sideAverage
            let sideDelta = abs(left - right)
            if center >= minimumLineLuminance &&
                centerContrast >= contrastThreshold &&
                sideDelta <= sideSimilarityThreshold {
                return true
            }
        }

        return false
    }

    private func luminanceValue(
        x: CGFloat,
        y: CGFloat,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        luminance: UnsafePointer<UInt8>
    ) -> Int? {
        let sampleX = Int(x.rounded())
        let sampleY = Int(y.rounded())
        guard sampleX >= 0, sampleY >= 0, sampleX < width, sampleY < height else {
            return nil
        }

        return Int(luminance[sampleY * bytesPerRow + sampleX])
    }

    private func normalizedDeltaForLineSnap(
        normalX: CGFloat,
        normalY: CGFloat,
        offset: CGFloat,
        orientation: CGImagePropertyOrientation,
        width: Int,
        height: Int
    ) -> CGPoint {
        let normalizedX = normalX * offset / CGFloat(max(width - 1, 1))
        let normalizedY = normalY * offset / CGFloat(max(height - 1, 1))

        switch orientation {
        case .right, .rightMirrored:
            return CGPoint(x: -normalizedY, y: normalizedX)
        case .left, .leftMirrored:
            return CGPoint(x: normalizedY, y: -normalizedX)
        case .down, .downMirrored:
            return CGPoint(x: -normalizedX, y: -normalizedY)
        default:
            return CGPoint(x: normalizedX, y: normalizedY)
        }
    }

    private func mapNormalizedPoint(
        _ point: CGPoint,
        orientation: CGImagePropertyOrientation,
        width: Int,
        height: Int
    ) -> CGPoint {
        let x = min(max(point.x, 0), 1)
        let y = min(max(point.y, 0), 1)

        switch orientation {
        case .right, .rightMirrored:
            return CGPoint(
                x: y * CGFloat(width - 1),
                y: (1 - x) * CGFloat(height - 1)
            )
        case .left, .leftMirrored:
            return CGPoint(
                x: (1 - y) * CGFloat(width - 1),
                y: x * CGFloat(height - 1)
            )
        case .down, .downMirrored:
            return CGPoint(
                x: (1 - x) * CGFloat(width - 1),
                y: (1 - y) * CGFloat(height - 1)
            )
        default:
            return CGPoint(
                x: x * CGFloat(width - 1),
                y: y * CGFloat(height - 1)
            )
        }
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
