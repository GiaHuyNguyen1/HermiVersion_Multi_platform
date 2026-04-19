import SwiftUI
import PhotosUI

/// iOS equivalent of Android VideoPickerUI + LiveAnalysis launcher
struct VideoPickerView: View {
    @Binding var path: NavigationPath
    @State private var selectedItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var isLoading = false
    
    var body: some View {
        ZStack {
            // Background gradient
            LinearGradient(
                colors: [Color(hex: "0A0E1A"), Color(hex: "111827")],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            VStack(spacing: 32) {
                Spacer()
                
                // Logo / branding
                VStack(spacing: 12) {
                    Image(systemName: "tennisball.fill")
                        .font(.system(size: 72))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    
                    Text("HermiVision")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    
                    Text("AI-Powered Tennis Analysis")
                        .font(.subheadline)
                        .foregroundColor(Color.white.opacity(0.5))
                }
                
                Spacer()
                
                // Action buttons
                VStack(spacing: 16) {
                    // Pick video button
                    PhotosPicker(selection: $selectedItem, matching: .videos) {
                        HStack {
                            Image(systemName: "video.badge.plus")
                                .font(.title3)
                            Text("Select Video")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 18)
                        .background(
                            LinearGradient(
                                colors: [Color(hex: "6EE7B7"), Color(hex: "3B82F6")],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .foregroundColor(.black)
                        .cornerRadius(16)
                    }
                    .onChange(of: selectedItem) { newItem in
                        handleVideoSelection(newItem)
                    }
                    
                    // Live camera button
                    Button {
                        path.append(AppRoute.liveAnalysis)
                    } label: {
                        HStack {
                            Image(systemName: "camera.fill")
                                .font(.title3)
                            Text("Live Camera")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 18)
                        .background(Color.white.opacity(0.08))
                        .foregroundColor(.white)
                        .cornerRadius(16)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color.white.opacity(0.15), lineWidth: 1)
                        )
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 48)
            }
            
            if isLoading {
                LoadingOverlay(message: "Preparing video…")
            }
        }
        .navigationBarHidden(true)
    }
    
    private func handleVideoSelection(_ item: PhotosPickerItem?) {
        guard let item else { return }
        isLoading = true
        Task {
            if let url = try? await item.loadTransferable(type: VideoTransferable.self) {
                await MainActor.run {
                    isLoading = false
                    path.append(AppRoute.optimizing)
                }
            } else {
                await MainActor.run { isLoading = false }
            }
        }
    }
}

// MARK: - Video Transferable
struct VideoTransferable: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { video in
            SentTransferredFile(video.url)
        } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(received.file.lastPathComponent)
            try? FileManager.default.copyItem(at: received.file, to: dest)
            return VideoTransferable(url: dest)
        }
    }
}
