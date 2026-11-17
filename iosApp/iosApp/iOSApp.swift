import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    // Starts drive tracking early so a background relaunch (location event) is handled even without a UI scene.
    init() { IosBootstrapKt.startTrackingOnLaunch() }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
