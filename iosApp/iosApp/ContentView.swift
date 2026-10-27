import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    // Compose draws behind the status bar, so the bar style follows the app theme resolved in
    // Kotlin; the initial value guesses the system setting until the bridge reports back.
    @State private var isDark: Bool = UITraitCollection.current.userInterfaceStyle == .dark

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .preferredColorScheme(isDark ? .dark : .light)
            .onAppear {
                StatusBarAppearance.shared.observe { value in
                    Task { @MainActor in
                        isDark = value.boolValue
                    }
                }
            }
    }
}
