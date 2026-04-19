import SwiftUI
import AVFoundation

/// iOS equivalent of Android LiveAnalysisScreen + LiveViewModel
struct LiveAnalysisView: View {
    @Binding var path: NavigationPath
    @StateObject private var viewModel = LiveViewModel()
    
    var body: some View {
        ZStack {
            // Camera preview
            CameraPreviewView(session: viewModel.captureSession)
                .ignoresSafeArea()

            if let cameraError = viewModel.cameraError {
                Color.black.opacity(0.78)
                    .ignoresSafeArea()

                VStack(spacing: 12) {
                    Image(systemName: "camera.slash.fill")
                        .font(.system(size: 40))
                        .foregroundColor(.white.opacity(0.75))

                    Text(cameraError)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundColor(.white)
                        .padding(.horizontal, 28)
                }
            }
            
            // Overlay: detection results
            GeometryReader { geo in
                ZStack {
                    if !viewModel.isStopped && viewModel.courtValid {
                        CourtOverlayView(
                            keypoints: viewModel.courtKeypoints,
                            containerSize: geo.size
                        )
                    }

                    // Ball detection bounding box
                    if !viewModel.isStopped && viewModel.ballVisible {
                        BallOverlayView(
                            rect: viewModel.ballRect,
                            containerSize: geo.size
                        )
                    }

                    if let inferenceMessage = viewModel.inferenceMessage,
                       viewModel.cameraError == nil,
                       !viewModel.isStopped {
                        VStack {
                            InferenceStatusBanner(message: inferenceMessage)
                                .padding(.top, 118)
                            Spacer()
                        }
                    }

                    if viewModel.isStopped {
                        SessionResultsOverlay(
                            sessionStats: viewModel.sessionStats,
                            onClose: { path.removeLast() }
                        )
                    } else {
                        LiveSessionHud(
                            fps: viewModel.displayFps,
                            delegate: viewModel.activeDelegate,
                            ballVisible: viewModel.ballVisible,
                            courtValid: viewModel.courtValid,
                            onStop: { viewModel.stopSession() },
                            onClose: { path.removeLast() }
                        )
                    }
                }
            }
        }
        .navigationBarHidden(true)
        .onAppear { viewModel.startCamera() }
        .onDisappear { viewModel.stopCamera() }
    }
}

struct CourtOverlayView: View {
    let keypoints: [CGPoint]
    let containerSize: CGSize

    var body: some View {
        ZStack {
            ForEach(Array(keypoints.enumerated()), id: \.offset) { entry in
                let point = entry.element
                Circle()
                    .fill(Color(hex: "3B82F6").opacity(0.75))
                    .frame(width: 9, height: 9)
                    .overlay(
                        Circle()
                            .stroke(Color.white.opacity(0.7), lineWidth: 1)
                    )
                    .position(
                        x: point.x * containerSize.width,
                        y: point.y * containerSize.height
                    )
            }
        }
    }
}

struct InferenceStatusBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.7))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.white.opacity(0.18), lineWidth: 1)
            )
            .padding(.horizontal, 18)
    }
}

// MARK: - Ball Overlay
struct BallOverlayView: View {
    let rect: CGRect
    let containerSize: CGSize
    
    var body: some View {
        let scaledRect = CGRect(
            x: rect.minX * containerSize.width,
            y: rect.minY * containerSize.height,
            width: rect.width * containerSize.width,
            height: rect.height * containerSize.height
        )
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .stroke(Color(hex: "6EE7B7"), lineWidth: 2)
                .frame(width: scaledRect.width, height: scaledRect.height)
                .position(x: scaledRect.midX, y: scaledRect.midY)
            
            Circle()
                .fill(Color(hex: "6EE7B7").opacity(0.4))
                .frame(width: 8, height: 8)
                .position(x: scaledRect.midX, y: scaledRect.midY)
        }
    }
}

// MARK: - HUD Panel
struct HUDPanelView: View {
    let fps: Int
    let delegate: String
    let ballVisible: Bool
    
    var body: some View {
        HStack(spacing: 12) {
            HUDChip(label: "\(fps) FPS", color: fps > 20 ? Color(hex: "6EE7B7") : Color(hex: "F59E0B"))
            HUDChip(label: delegate, color: Color(hex: "3B82F6"))
            HUDChip(label: ballVisible ? "● BALL" : "○ —", color: ballVisible ? Color(hex: "6EE7B7") : Color.white.opacity(0.4))
            Spacer()
        }
    }
}

struct HUDChip: View {
    let label: String
    let color: Color
    
    var body: some View {
        Text(label)
            .font(.system(size: 12, weight: .semibold, design: .monospaced))
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.6))
            .cornerRadius(8)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(color.opacity(0.4), lineWidth: 1))
    }
}

// MARK: - ViewModel
@MainActor
class LiveViewModel: ObservableObject {
    struct SessionStats {
        let totalFrames: Int
        let ballDetections: Int
        let courtDetections: Int
        let avgFps: Double
        let durationMs: Int

        static let empty = SessionStats(
            totalFrames: 0,
            ballDetections: 0,
            courtDetections: 0,
            avgFps: 0,
            durationMs: 0
        )
    }

    let captureSession = AVCaptureSession()
    @Published var ballVisible = false
    @Published var ballRect = CGRect.zero
    @Published var displayFps = 0
    @Published var activeDelegate = "Preview"
    @Published var courtValid = false
    @Published var courtKeypoints: [CGPoint] = []
    @Published var cameraError: String?
    @Published var inferenceMessage: String?
    @Published var isStopped = false
    @Published var sessionStats = SessionStats.empty
    
    private var cameraController: AVFoundationCameraController?
    
    func startCamera() {
        guard cameraController == nil else { return }
        ballVisible = false
        ballRect = .zero
        displayFps = 0
        courtValid = false
        courtKeypoints = []
        cameraError = nil
        inferenceMessage = nil
        isStopped = false
        sessionStats = .empty
        NSLog("[HermiVision][iOS] Live camera requested")

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            NSLog("[HermiVision][iOS] Camera permission already authorized")
            configureAndStartCamera()
        case .notDetermined:
            NSLog("[HermiVision][iOS] Requesting camera permission")
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if granted {
                        NSLog("[HermiVision][iOS] Camera permission granted")
                        self.configureAndStartCamera()
                    } else {
                        NSLog("[HermiVision][iOS] Camera permission denied by user")
                        self.cameraError = "Camera access is required for live analysis."
                    }
                }
            }
        case .denied, .restricted:
            NSLog("[HermiVision][iOS] Camera permission unavailable: \(AVCaptureDevice.authorizationStatus(for: .video).rawValue)")
            cameraError = "Camera access is disabled. Enable it in Settings to use live analysis."
        @unknown default:
            NSLog("[HermiVision][iOS] Camera permission hit unknown authorization state")
            cameraError = "Camera access is unavailable on this device."
        }
    }

    private func configureAndStartCamera() {
        NSLog("[HermiVision][iOS] Creating camera controller")
        cameraController = AVFoundationCameraController(session: captureSession)
        cameraController?.onFrame = { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                guard self.ballVisible != result.ballVisible
                    || self.ballRect != result.ballRect
                    || self.courtValid != result.courtValid
                    || self.courtKeypoints != result.courtKeypoints
                    || self.activeDelegate != result.runtimeLabel
                else {
                    return
                }
                self.ballVisible = result.ballVisible
                self.ballRect = result.ballRect
                self.courtValid = result.courtValid
                self.courtKeypoints = result.courtKeypoints
                self.activeDelegate = result.runtimeLabel
            }
        }
        cameraController?.onStatus = { [weak self] availability in
            Task { @MainActor in
                guard let self else { return }
                self.activeDelegate = availability.runtimeLabel
                self.inferenceMessage = availability.message
            }
        }
        cameraController?.onMetrics = { [weak self] snapshot in
            guard let self else { return }
            self.displayFps = Int(snapshot.avgFps.rounded())
            self.activeDelegate = snapshot.runtimeLabel
        }
        cameraController?.onError = { [weak self] message in
            NSLog("[HermiVision][iOS] Camera error: \(message)")
            self?.cameraError = message
        }
        cameraController?.start()
    }

    func stopSession() {
        guard !isStopped else { return }
        NSLog("[HermiVision][iOS] Live session stopping for results")
        let snapshot = cameraController?.currentSessionSnapshot() ??
            AVFoundationCameraController.SessionSnapshot(
                totalFrames: 0,
                ballDetections: 0,
                courtDetections: 0,
                avgFps: 0,
                durationMs: 0,
                runtimeLabel: activeDelegate
            )
        sessionStats = SessionStats(
            totalFrames: snapshot.totalFrames,
            ballDetections: snapshot.ballDetections,
            courtDetections: snapshot.courtDetections,
            avgFps: snapshot.avgFps,
            durationMs: snapshot.durationMs
        )
        isStopped = true
        stopCamera()
    }
    
    func stopCamera() {
        NSLog("[HermiVision][iOS] Live camera stopping")
        cameraController?.stop()
        cameraController = nil
    }
}

struct LiveSessionHud: View {
    let fps: Int
    let delegate: String
    let ballVisible: Bool
    let courtValid: Bool
    let onStop: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack {
            HStack {
                HUDChip(label: "\(fps) FPS", color: fps > 20 ? Color(hex: "6EE7B7") : Color(hex: "F59E0B"))
                HUDChip(label: delegate, color: Color(hex: "3B82F6"))
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 36))
                        .foregroundColor(.white.opacity(0.8))
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 60)

            Spacer()

            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    StatusPill(
                        title: "Ball",
                        value: ballVisible ? "Detected" : "—",
                        color: ballVisible ? Color(hex: "6EE7B7") : Color.white.opacity(0.4)
                    )
                    StatusPill(
                        title: "Court",
                        value: courtValid ? "Detected" : "—",
                        color: courtValid ? Color(hex: "3B82F6") : Color.white.opacity(0.4)
                    )
                }

                Spacer()

                Button(action: onStop) {
                    Text("STOP")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 140, height: 48)
                        .background(Color.red.opacity(0.88))
                        .clipShape(Capsule())
                }

                Spacer()

                StatusPill(
                    title: "Status",
                    value: "LIVE",
                    color: Color.red.opacity(0.9)
                )
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 48)
        }
    }
}

struct StatusPill: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text("\(title): \(value)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.white)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

struct SessionResultsOverlay: View {
    let sessionStats: LiveViewModel.SessionStats
    let onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.88)
                .ignoresSafeArea()

            VStack(spacing: 22) {
                Text("Session Results")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.white)

                HStack(spacing: 18) {
                    SessionStatCard(title: "Frames", value: "\(sessionStats.totalFrames)", color: Color(hex: "3B82F6"))
                    SessionStatCard(title: "Ball Hits", value: "\(sessionStats.ballDetections)", color: Color(hex: "6EE7B7"))
                    SessionStatCard(title: "Court", value: "\(sessionStats.courtDetections)", color: Color(hex: "F59E0B"))
                }

                HStack(spacing: 18) {
                    let detectionRate = sessionStats.totalFrames > 0
                        ? (Double(sessionStats.ballDetections) * 100.0 / Double(sessionStats.totalFrames))
                        : 0
                    SessionStatCard(title: "Detect Rate", value: String(format: "%.1f%%", detectionRate), color: Color(hex: "F59E0B"))
                    SessionStatCard(title: "Avg FPS", value: String(format: "%.1f", sessionStats.avgFps), color: Color(hex: "6EE7B7"))
                    SessionStatCard(title: "Duration", value: String(format: "%.1fs", Double(sessionStats.durationMs) / 1000.0), color: .white)
                }

                Button(action: onClose) {
                    Text("Done")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 200, height: 48)
                        .background(Color(hex: "F59E0B"))
                        .clipShape(Capsule())
                }
                .padding(.top, 8)
            }
            .padding(32)
        }
    }
}

struct SessionStatCard: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Text(value)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(color)
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.white.opacity(0.65))
        }
        .frame(width: 100)
        .padding(.vertical, 12)
        .background(Color.white.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Camera Preview (UIViewRepresentable)
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> UIView {
        let view = CameraPreviewContainerView()
        view.previewLayer.videoGravity = .resizeAspectFill
        view.previewLayer.session = session
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        guard let previewView = uiView as? CameraPreviewContainerView else { return }
        if previewView.previewLayer.session !== session {
            previewView.previewLayer.session = session
        }
    }
}

final class CameraPreviewContainerView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        guard let layer = layer as? AVCaptureVideoPreviewLayer else {
            fatalError("Expected AVCaptureVideoPreviewLayer backing layer")
        }
        return layer
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}
