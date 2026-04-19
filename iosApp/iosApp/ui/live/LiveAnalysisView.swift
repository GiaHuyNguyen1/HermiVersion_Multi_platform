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
            
            // Overlay: detection results
            GeometryReader { geo in
                ZStack {
                    // Ball detection bounding box
                    if viewModel.ballVisible {
                        BallOverlayView(
                            rect: viewModel.ballRect,
                            containerSize: geo.size
                        )
                    }
                    
                    // HUD panel (top)
                    VStack {
                        HUDPanelView(
                            fps: viewModel.displayFps,
                            delegate: viewModel.activeDelegate,
                            ballVisible: viewModel.ballVisible
                        )
                        .padding(.horizontal, 16)
                        .padding(.top, 60)
                        
                        Spacer()
                        
                        // Bottom controls
                        HStack {
                            Button {
                                path.removeLast()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 36))
                                    .foregroundColor(.white.opacity(0.8))
                            }
                            
                            Spacer()
                            
                            // Mini court map
                            if viewModel.courtValid {
                                MiniCourtMapView(isValid: true)
                                    .frame(width: 65, height: 141)
                                    .cornerRadius(8)
                                    .padding(8)
                                    .background(Color.black.opacity(0.5))
                                    .cornerRadius(12)
                            }
                        }
                        .padding(.horizontal, 24)
                        .padding(.bottom, 48)
                    }
                }
            }
        }
        .navigationBarHidden(true)
        .onAppear { viewModel.startCamera() }
        .onDisappear { viewModel.stopCamera() }
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
    let captureSession = AVCaptureSession()
    @Published var ballVisible = false
    @Published var ballRect = CGRect.zero
    @Published var displayFps = 0
    @Published var activeDelegate = "CoreML"
    @Published var courtValid = false
    
    private var cameraController: AVFoundationCameraController?
    
    func startCamera() {
        cameraController = AVFoundationCameraController(session: captureSession)
        cameraController?.onFrame = { [weak self] ballVisible, ballRect in
            Task { @MainActor in
                self?.ballVisible = ballVisible
                self?.ballRect = ballRect
            }
        }
        cameraController?.start()
    }
    
    func stopCamera() {
        cameraController?.stop()
        cameraController = nil
    }
}

// MARK: - Camera Preview (UIViewRepresentable)
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        if let layer = uiView.layer.sublayers?.first as? AVCaptureVideoPreviewLayer {
            layer.frame = uiView.bounds
        }
    }
}
