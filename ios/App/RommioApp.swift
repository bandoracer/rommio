import SwiftUI
import RommioUI

@main
struct RommioApp: App {
    var body: some Scene {
        WindowGroup {
            RommioBootstrapView()
        }
    }
}

private struct RommioBootstrapView: View {
    @State private var rootView: AnyView?
    @State private var launchError: String?

    var body: some View {
        Group {
            if let rootView {
                rootView
            } else if let launchError {
                ContentUnavailableView(
                    "Unable to launch Rommio",
                    systemImage: "exclamationmark.triangle.fill",
                    description: Text(launchError)
                )
            } else {
                ProgressView("Launching Rommio")
                    .task {
                        await prepareRootView()
                    }
            }
        }
    }

    @MainActor
    private func prepareRootView() async {
        do {
            rootView = AnyView(try RommioRootView.live())
        } catch {
            launchError = error.localizedDescription
        }
    }
}
