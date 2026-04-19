import SwiftUI

/// iOS equivalent of Android ResultsScreen + ResultsViewModel
struct ResultsView: View {
    @Binding var path: NavigationPath
    @StateObject private var viewModel = ResultsViewModel()
    
    var body: some View {
        ZStack {
            Color(hex: "0A0E1A").ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    // Header
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Analysis Results")
                                .font(.system(size: 26, weight: .bold))
                                .foregroundColor(.white)
                            Text(viewModel.sessionLabel)
                                .font(.caption)
                                .foregroundColor(Color.white.opacity(0.5))
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 20)
                    
                    // Key metrics grid
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                        MetricCard(
                            title: "Detection Rate",
                            value: String(format: "%.1f%%", viewModel.detectionRate * 100),
                            icon: "scope",
                            color: Color(hex: "6EE7B7")
                        )
                        MetricCard(
                            title: "Total Frames",
                            value: "\(viewModel.totalFrames)",
                            icon: "film.stack",
                            color: Color(hex: "3B82F6")
                        )
                        MetricCard(
                            title: "Avg Confidence",
                            value: String(format: "%.2f", viewModel.avgConfidence),
                            icon: "chart.bar.fill",
                            color: Color(hex: "F59E0B")
                        )
                        MetricCard(
                            title: "Effective FPS",
                            value: String(format: "%.1f", viewModel.effectiveFps),
                            icon: "speedometer",
                            color: Color(hex: "EF4444")
                        )
                    }
                    .padding(.horizontal, 24)
                    
                    // Court map placeholder
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Court Detection")
                            .font(.headline)
                            .foregroundColor(.white)
                        
                        MiniCourtMapView(isValid: viewModel.courtValid)
                            .frame(maxWidth: .infinity)
                            .frame(height: 200)
                            .background(Color.white.opacity(0.05))
                            .cornerRadius(16)
                    }
                    .padding(.horizontal, 24)
                    
                    // Action buttons
                    HStack(spacing: 16) {
                        Button {
                            path.removeLast(path.count)  // Go to root
                        } label: {
                            Label("New Video", systemImage: "plus.circle.fill")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color.white.opacity(0.08))
                                .foregroundColor(.white)
                                .cornerRadius(14)
                        }
                        
                        Button {
                            // TODO: Export results
                        } label: {
                            Label("Export", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(
                                    LinearGradient(
                                        colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                        startPoint: .leading, endPoint: .trailing
                                    )
                                )
                                .foregroundColor(.black)
                                .cornerRadius(14)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 40)
                }
            }
        }
        .navigationBarHidden(true)
    }
}

// MARK: - Sub-components

struct MetricCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: icon)
                    .foregroundColor(color)
                    .font(.title3)
                Spacer()
            }
            Text(value)
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundColor(.white)
            Text(title)
                .font(.caption)
                .foregroundColor(Color.white.opacity(0.5))
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(color.opacity(0.2), lineWidth: 1)
        )
    }
}

struct MiniCourtMapView: View {
    let isValid: Bool
    
    var body: some View {
        ZStack {
            if isValid {
                // TODO: draw keypoints from shared KMP CourtHomography
                Image(systemName: "rectangle.on.rectangle.square")
                    .font(.system(size: 48))
                    .foregroundColor(Color(hex: "6EE7B7").opacity(0.4))
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "eye.slash")
                        .font(.system(size: 32))
                        .foregroundColor(Color.white.opacity(0.2))
                    Text("Court not detected")
                        .font(.caption)
                        .foregroundColor(Color.white.opacity(0.3))
                }
            }
        }
    }
}

@MainActor
class ResultsViewModel: ObservableObject {
    // TODO: observe ProcessingResultRepository from :shared
    @Published var detectionRate: Float = 0.87
    @Published var totalFrames: Int = 1000
    @Published var avgConfidence: Float = 0.76
    @Published var effectiveFps: Double = 21.4
    @Published var courtValid: Bool = true
    @Published var sessionLabel: String = "Session — just now"
}
