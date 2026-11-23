import ActivityKit
import SwiftUI
import WidgetKit

struct LeashLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LeashActivityAttributes.self) { context in
            Text(context.state.title)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text(context.state.title) }
            } compactLeading: {
                Image(systemName: "sparkle")
            } compactTrailing: {
                EmptyView()
            } minimal: {
                Image(systemName: "sparkle")
            }
        }
    }
}
