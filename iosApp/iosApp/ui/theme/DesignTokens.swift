import SwiftUI

// MARK: - Shared design tokens matching Android LiveDesignTokens
extension Color {
    static let hvGreen  = Color(hex: "6EE7B7")
    static let hvBlue   = Color(hex: "3B82F6")
    static let hvAmber  = Color(hex: "F59E0B")
    static let hvRed    = Color(hex: "EF4444")
    static let hvBg     = Color(hex: "0A0E1A")
    static let hvSurface = Color(hex: "111827")
}

// MARK: - Shared loading overlay
struct LoadingOverlay: View {
    let message: String
    
    var body: some View {
        ZStack {
            Color.black.opacity(0.7).ignoresSafeArea()
            VStack(spacing: 16) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.hvGreen)
                    .scaleEffect(1.5)
                Text(message)
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.8))
            }
        }
    }
}

// MARK: - Hex color initializer
extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:(a, r, g, b) = (1, 1, 1, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
