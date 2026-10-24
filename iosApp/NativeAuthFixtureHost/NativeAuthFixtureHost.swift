import AuthFixture
import SwiftUI

// Test-only application host: gives XCTest real app Keychain context, never linked into iosApp.
@main
struct NativeAuthFixtureHost: App {
    var body: some Scene {
        WindowGroup { Text(NativeAuthPersistenceFixtureKt.nativeAuthFixtureHostMarker()) }
    }
}
