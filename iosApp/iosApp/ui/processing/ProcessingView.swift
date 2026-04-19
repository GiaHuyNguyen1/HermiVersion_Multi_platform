import SwiftUI

/// iOS equivalent of Android ProcessingScreen + ProcessingViewModel
struct ProcessingView: View {
    let videoPath: String
    @Binding var path: NavigationPath
    
    @StateObject private var viewModel = ProcessingViewModel()
    
    var body: some View {
        ZStack {
            Color(hex: "0A0E1A").ignoresSafeArea()
            
            VStack(spacing: 40) {
                Spacer()
                
                // Animated ring progress
                ZStack {
                    Circle()
                        .stroke(Color.white.opacity(0.1), lineWidth: 8)
                        .frame(width: 140, height: 140)
                    
                    Circle()
                        .trim(from: 0, to: CGFloat(viewModel.progress) / 100.0)
                        .stroke(
                            LinearGradient(
                                colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            style: StrokeStyle(lineWidth: 8, lineCap: .round)
                        )
                        .frame(width: 140, height: 140)
                        .rotationEffect(.degrees(-90))
                        .animation(.easeInOut(duration: 0.3), value: viewModel.progress)
                    
                    VStack(spacing: 4) {
                        Text("\(viewModel.progress)%")
                            .font(.system(size: 28, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                        Text("Frames")
                            .font(.caption)
                            .foregroundColor(Color.white.opacity(0.5))
                    }
                }
                
                // Status text
                VStack(spacing: 8) {
                    Text(viewModel.statusMessage)
                        .font(.headline)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                    
                    if viewModel.fps > 0 {
                        Text(String(format: "%.1f FPS", viewModel.fps))
                            .font(.caption)
                            .foregroundColor(Color(hex: "6EE7B7"))
                    }
                }
                
                Spacer()
                
                // Cancel button
                Button("Cancel") {
                    viewModel.cancel()
                    path.removeLast()
                }
                .foregroundColor(Color.white.opacity(0.5))
                .padding(.bottom, 40)
            }
            .padding(.horizontal, 32)
        }
        .navigationBarHidden(true)
        .onAppear { viewModel.startProcessing(videoPath: videoPath) }
        .onChange(of: viewModel.isComplete) { complete in
            if complete { path.append(AppRoute.results) }
        }
    }
}

@MainActor
class ProcessingViewModel: ObservableObject {
    @Published var progress: Int = 0
    @Published var statusMessage = "Initialising AI pipeline…"
    @Published var fps: Double = 0
    @Published var isComplete = false
    
    private var task: Task<Void, Never>?
    
    func startProcessing(videoPath: String) {
        task = Task {
            // TODO: call :shared KMP processing logic
            // Simulate progress for Phase 1
            for i in 1...100 {
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: 50_000_000)
                progress = i
                statusMessage = "Processing frame \(i * 10) / 1000"
                fps = Double.random(in: 18...24)
            }
            isComplete = true
        }
    }
    
    func cancel() { task?.cancel() }
}
