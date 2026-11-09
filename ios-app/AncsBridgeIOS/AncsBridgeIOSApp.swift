import SwiftUI

@main
struct AncsBridgeIOSApp: App {
    @StateObject private var bleManager = WatchBleCentralManager.shared
    @StateObject private var healthManager = HealthKitSyncManager.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
