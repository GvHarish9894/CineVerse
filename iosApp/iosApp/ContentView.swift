import ComposeApp
import SwiftUI
import UIKit

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Written into the built Info.plist from secrets.properties by the "Inject native read token" build phase.
        let token = Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String
        return ApprovalHostController(content: MainViewControllerKt.MainViewController(nativeReadToken: token))
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: ()) {
        (uiViewController as? ApprovalHostController)?.detachApprovalWindow()
    }
}

// Thin host only: the session provider has a weak window and is detached before this controller disappears.
private final class ApprovalHostController: UIViewController {
    private let content: UIViewController
    private weak var approvalWindow: UIWindow?

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.frame = view.bounds
        content.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(content.view)
        content.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let window = view.window {
            approvalWindow = window
            NativeApprovalHostKt.attachNativeApprovalWindow(window: window)
        }
    }

    func detachApprovalWindow() {
        if let window = approvalWindow {
            NativeApprovalHostKt.detachNativeApprovalWindow(window: window)
        }
        approvalWindow = nil
    }
}
