import SwiftUI

@main
struct LeashApp: App {
    @State private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Live Activity buttons and watch messages can start the app in the background, before (or without) any
        // scene: the handlers have to exist from launch.
        let model = AppModel.shared
        LiveActivityActions.handler = { action in await model.perform(action) }
        WatchRelay.shared.start(model: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onOpenURL { model.open($0) }
                .onChange(of: scenePhase, initial: true) { _, phase in model.scenePhaseChanged(phase) }
                .tint(.accentColor)
        }
    }
}
