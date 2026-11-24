import SwiftUI
import WidgetKit

/// The extension only provides the Live Activity (Lock Screen, Dynamic Island, Apple Watch Smart Stack):
/// no Home Screen widgets, no controls.
@main
struct LeashLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        LeashLiveActivity()
    }
}
