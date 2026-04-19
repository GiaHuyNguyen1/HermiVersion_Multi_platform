import SwiftUI

/// iOS equivalent of Android OptimizingScreen + OptimizingViewModel
/// Benchmarks device hardware to pick best AI delegate and saves AIConfig.
struct OptimizingView: View {
    @Binding var path: NavigationPath
    @StateObject private var viewModel = OptimizingViewModel()
    
    var body: some View {
        ZStack {
            Color(hex: "0A0E1A").ignoresSafeArea()
            
            VStack(spacing: 32) {
                Spacer()
                
                // Animated scanner icon
                ZStack {
                    ForEach(0..<3) { i in
                        Circle()
                            .stroke(Color(hex: "6EE7B7").opacity(0.15 - Double(i) * 0.04), lineWidth: 1)
                            .frame(width: CGFloat(120 + i * 40), height: CGFloat(120 + i * 40))
                            .scaleEffect(viewModel.isRunning ? 1.08 : 1.0)
                            .animation(
                                .easeInOut(duration: 1.2).repeatForever().delay(Double(i) * 0.3),
                                value: viewModel.isRunning
                            )
                    }
                    
                    Image(systemName: "cpu.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                }
                
                // Title + status
                VStack(spacing: 8) {
                    Text("Optimising for your device")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)
                    
                    Text(viewModel.statusMessage)
                        .font(.subheadline)
                        .foregroundColor(Color.white.opacity(0.5))
                        .multilineTextAlignment(.center)
                }
                
                // Progress bar
                VStack(spacing: 8) {
                    ProgressView(value: Double(viewModel.progress), total: 100)
                        .progressViewStyle(.linear)
                        .tint(Color(hex: "6EE7B7"))
                        .background(Color.white.opacity(0.1))
                        .cornerRadius(4)
                    
                    HStack {
                        Text("\(viewModel.progress)%")
                            .font(.caption)
                            .foregroundColor(Color(hex: "6EE7B7"))
                        Spacer()
                    }
                }
                .padding(.horizontal, 32)
                
                // Benchmark results summary
                if let config = viewModel.completedConfig {
                    BenchmarkResultCard(config: config)
                        .padding(.horizontal, 24)
                }
                
                Spacer()
                
                // Continue button (shown when done)
                if viewModel.isDone {
                    Button {
                        // Navigate back to picker or to processing
                        path.removeLast(path.count)
                    } label: {
                        Text("Continue")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                            .background(
                                LinearGradient(
                                    colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                    startPoint: .leading, endPoint: .trailing
                                )
                            )
                            .foregroundColor(.black)
                            .cornerRadius(16)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 48)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .navigationBarHidden(true)
        .onAppear { viewModel.startBenchmark() }
    }
}

// MARK: - Benchmark result card
struct BenchmarkResultCard: View {
    let config: OptimizingViewModel.DeviceResult
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Benchmark Results")
                .font(.caption)
                .foregroundColor(Color.white.opacity(0.5))
                .textCase(.uppercase)
                .kerning(1)
            
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Best Delegate")
                        .font(.caption2).foregroundColor(Color.white.opacity(0.4))
                    Text(config.bestDelegate)
                        .font(.headline).foregroundColor(Color(hex: "6EE7B7"))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("Tier").font(.caption2).foregroundColor(Color.white.opacity(0.4))
                    Text(config.tier).font(.headline).foregroundColor(.white)
                }
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .cornerRadius(16)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(hex: "6EE7B7").opacity(0.2), lineWidth: 1))
    }
}

@MainActor
class OptimizingViewModel: ObservableObject {
    struct DeviceResult {
        let bestDelegate: String
        let tier: String
        let latencyMs: Int
    }
    
    @Published var progress = 0
    @Published var statusMessage = "Running CoreML benchmark…"
    @Published var isRunning = false
    @Published var isDone = false
    @Published var completedConfig: DeviceResult?
    
    func startBenchmark() {
        isRunning = true
        Task {
            // TODO: call DeviceProfiler from :shared KMP
            // Phase 1: simulated benchmark
            for step in 1...100 {
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: 80_000_000)
                progress = step
                switch step {
                case 1...30: statusMessage = "Benchmarking CoreML delegate…"
                case 31...60: statusMessage = "Benchmarking Metal GPU delegate…"
                case 61...90: statusMessage = "Benchmarking CPU fallback…"
                default:      statusMessage = "Finalising configuration…"
                }
            }
            completedConfig = DeviceResult(bestDelegate: "CoreML", tier: "HIGH", latencyMs: 42)
            isDone = true
            isRunning = false
        }
    }
}
