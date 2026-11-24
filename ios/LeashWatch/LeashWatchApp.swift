import SwiftUI

@main
struct LeashWatchApp: App {
    @State private var model = WatchModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(model)
                .onChange(of: scenePhase) { _, phase in if phase == .active { model.refresh() } }
        }
    }
}
