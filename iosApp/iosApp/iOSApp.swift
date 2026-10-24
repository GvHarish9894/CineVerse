import ComposeApp
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Written into the built Info.plist from secrets.properties by the "Inject native read token" build phase.
        InitKoin_iosKt.doInitKoin(nativeReadToken: Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String)
    }

    var body: some Scene {
        WindowGroup {
            ContentView().ignoresSafeArea()
                .onOpenURL { NativeApprovalHostKt.handleNativeApprovalUrl(uri: $0.absoluteString) }
                .onChange(of: scenePhase) { phase in
                    NativeApprovalHostKt.handleNativeApprovalScenePhase(isBackground: phase == .background)
                }
        }
    }
}
