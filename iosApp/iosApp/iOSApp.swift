import ComposeApp
import SwiftUI

@main
struct iOSApp: App {
    init() {
        // Written into the built Info.plist from secrets.properties by the "Inject native read token" build phase.
        InitKoin_iosKt.doInitKoin(nativeReadToken: Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String)
    }

    var body: some Scene {
        WindowGroup {
            ContentView().ignoresSafeArea()
        }
    }
}
