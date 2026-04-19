import AVFoundation
import CoreImage
import UIKit

/// iOS equivalent of Android CameraAnalyzer.
/// Uses AVCaptureSession with a video data output to deliver frames.
/// Frame inference is delegated to IosInferencePipeline (expect/actual stub).
final class AVFoundationCameraController: NSObject {
    
    typealias FrameCallback = (Bool, CGRect) -> Void
    
    var onFrame: FrameCallback?
    
    private let session: AVCaptureSession
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "com.hermitech.hermivision.camera")
    private let inferencePipeline = IosInferencePipeline()
    
    init(session: AVCaptureSession) {
        self.session = session
        super.init()
    }
    
    func start() {
        sessionQueue.async { [weak self] in
            self?.configureSession()
            self?.session.startRunning()
        }
    }
    
    func stop() {
        sessionQueue.async { [weak self] in
            self?.session.stopRunning()
        }
    }
    
    private func configureSession() {
        session.beginConfiguration()
        session.sessionPreset = .hd1280x720
        
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
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
        }
        
        session.commitConfiguration()
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
extension AVFoundationCameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        
        // TODO: call :shared KMP INativePipeline when iOS implementation is ready
        // Phase 1: delegate to IosInferencePipeline stub
        let result = inferencePipeline.process(pixelBuffer: pixelBuffer)
        onFrame?(result.ballVisible, result.ballRect)
    }
}
