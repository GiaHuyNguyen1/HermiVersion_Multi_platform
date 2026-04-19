import SwiftUI

/// Root navigation view mirroring Android's NavGraph.
/// Screens: VideoPicker → Processing → Results → LiveAnalysis | Optimizing
struct ContentView: View {
    @State private var path = NavigationPath()
    
    var body: some View {
        NavigationStack(path: $path) {
            VideoPickerView(path: $path)
                .navigationDestination(for: AppRoute.self) { route in
                    switch route {
                    case .processing(let videoPath):
                        ProcessingView(videoPath: videoPath, path: $path)
                    case .results:
                        ResultsView(path: $path)
                    case .liveAnalysis:
                        LiveAnalysisView(path: $path)
                    case .optimizing:
                        OptimizingView(path: $path)
                    }
                }
        }
        .preferredColorScheme(.dark)
    }
}

enum AppRoute: Hashable {
    case processing(videoPath: String)
    case results
    case liveAnalysis
    case optimizing
}

#Preview {
    ContentView()
}
