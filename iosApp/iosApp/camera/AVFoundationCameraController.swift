import AVFoundation
import CoreImage
import UIKit

/// iOS equivalent of Android CameraAnalyzer.
/// Uses AVCaptureSession with a video data output to deliver frames.
/// Frame inference is delegated to IosInferencePipeline.
final class AVFoundationCameraController: NSObject {
    
    typealias FrameCallback = (IosInferencePipeline.LiveInferenceResult) -> Void
    typealias ErrorCallback = (String) -> Void
    typealias MetricsCallback = (SessionSnapshot) -> Void
    typealias StatusCallback = (IosInferencePipeline.Availability) -> Void

    struct SessionSnapshot {
        let totalFrames: Int
        let ballDetections: Int
        let courtDetections: Int
        let avgFps: Double
        let durationMs: Int
        let runtimeLabel: String
    }
    
    var onFrame: FrameCallback?
    var onError: ErrorCallback?
    var onMetrics: MetricsCallback?
    var onStatus: StatusCallback?
    
    private let session: AVCaptureSession
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "com.hermitech.hermivision.camera")
    private let inferencePipeline = IosInferencePipeline()
    private var isConfigured = false
    private var hasLoggedFirstFrame = false
    private var lastInferenceResult: IosInferencePipeline.LiveInferenceResult?
    private var sessionStartTimeNs: UInt64 = 0
    private var totalFramesProcessed = 0
    private var totalBallDetections = 0
    private var totalCourtDetections = 0
    private var lastMetricsReportFrame = 0
    private var runtimeLabel = "Preview"
    private let liveVideoOrientation: AVCaptureVideoOrientation = .landscapeRight
    
    init(session: AVCaptureSession) {
        self.session = session
        super.init()
    }
    
    func start() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            NSLog("[HermiVision][iOS] Camera start requested")
            if !self.isConfigured {
                self.configureSession()
            }
            guard self.isConfigured else { return }
            let availability = self.inferencePipeline.loadIfNeeded()
            self.runtimeLabel = availability.runtimeLabel
            DispatchQueue.main.async { [weak self] in
                self?.onStatus?(availability)
            }
            guard !self.session.isRunning else { return }
            self.resetSessionStats()
            self.session.startRunning()
            NSLog("[HermiVision][iOS] Camera session is running")
        }
    }
    
    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            NSLog("[HermiVision][iOS] Camera stop requested")
            self.videoOutput.setSampleBufferDelegate(nil, queue: nil)
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.inferencePipeline.release()
            self.session.beginConfiguration()
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }
            self.session.commitConfiguration()
            self.isConfigured = false
            self.hasLoggedFirstFrame = false
            self.lastInferenceResult = nil
            self.runtimeLabel = "Preview"
        }
    }
    
    private func configureSession() {
        NSLog("[HermiVision][iOS] Configuring AVCaptureSession")
        session.beginConfiguration()
        session.sessionPreset = .hd1280x720
        
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            reportError("Unable to configure the back camera.")
            return
        }
        session.addInput(input)
        
        videoOutput.setSampleBufferDelegate(self, queue: sessionQueue)
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        
        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
            applyLandscapeVideoOrientation(to: videoOutput.connection(with: .video))
        } else {
            session.commitConfiguration()
            reportError("Unable to attach the camera output.")
            return
        }
        
        session.commitConfiguration()
        isConfigured = true
        NSLog("[HermiVision][iOS] AVCaptureSession configured")
    }

    private func applyLandscapeVideoOrientation(to connection: AVCaptureConnection?) {
        guard let connection, connection.isVideoOrientationSupported else {
            return
        }

        connection.videoOrientation = liveVideoOrientation
    }
    
    private func reportError(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.onError?(message)
        }
    }

    func currentSessionSnapshot() -> SessionSnapshot {
        sessionQueue.sync {
            buildSnapshot()
        }
    }

    private func resetSessionStats() {
        sessionStartTimeNs = DispatchTime.now().uptimeNanoseconds
        totalFramesProcessed = 0
        totalBallDetections = 0
        totalCourtDetections = 0
        lastMetricsReportFrame = 0
    }

    private func buildSnapshot() -> SessionSnapshot {
        let now = DispatchTime.now().uptimeNanoseconds
        let durationNs = max(now - sessionStartTimeNs, 0)
        let durationMs = Int(durationNs / 1_000_000)
        let avgFps = durationNs > 0 ? Double(totalFramesProcessed) / (Double(durationNs) / 1_000_000_000.0) : 0
        return SessionSnapshot(
            totalFrames: totalFramesProcessed,
            ballDetections: totalBallDetections,
            courtDetections: totalCourtDetections,
            avgFps: avgFps,
            durationMs: durationMs,
            runtimeLabel: runtimeLabel
        )
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
extension AVFoundationCameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if !hasLoggedFirstFrame {
            hasLoggedFirstFrame = true
            NSLog("[HermiVision][iOS] First camera frame received")
        }
        
        totalFramesProcessed += 1
        let result = inferencePipeline.process(pixelBuffer: pixelBuffer, orientation: .up)
        if let result {
            runtimeLabel = result.runtimeLabel
            if result.ballVisible {
                totalBallDetections += 1
            }
            if result.courtValid {
                totalCourtDetections += 1
            }
        } else {
            runtimeLabel = inferencePipeline.availability.runtimeLabel
        }
        if totalFramesProcessed - lastMetricsReportFrame >= 10 {
            lastMetricsReportFrame = totalFramesProcessed
            let snapshot = buildSnapshot()
            DispatchQueue.main.async { [weak self] in
                self?.onMetrics?(snapshot)
            }
        }
        guard let result else { return }
        guard result != lastInferenceResult else { return }
        lastInferenceResult = result
        onFrame?(result)
    }
}
