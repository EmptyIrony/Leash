import SwiftUI

@main
struct LeashApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onOpenURL { model.pair(with: $0) }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.foreground() }
                }
                .tint(.accentColor)
        }
    }
}
