import ComposeApp
import SwiftUI
import UIKit

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Written into the built Info.plist from secrets.properties by the "Inject native read token" build phase.
        let token = Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String
        return MainViewControllerKt.MainViewController(nativeReadToken: token)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
