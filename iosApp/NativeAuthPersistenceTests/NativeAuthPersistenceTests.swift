import AuthFixture
import XCTest

final class NativeAuthPersistenceTests: XCTestCase {
    func testSeed() throws {
        let pid = try NativeAuthPersistenceFixtureKt.runNativeAuthPersistenceFixture(phase: "seed")
        XCTAssertEqual(pid, ProcessInfo.processInfo.processIdentifier)
        UserDefaults.standard.set(pid, forKey: "c1_fixture_seed_pid")
        print("C1 Keychain fixture seeded; runner PID=\(pid)")
    }

    func testReloadInAnotherProcess() throws {
        let original = UserDefaults.standard.integer(forKey: "c1_fixture_seed_pid")
        XCTAssertNotEqual(original, 0)
        let pid = try NativeAuthPersistenceFixtureKt.runNativeAuthPersistenceFixture(phase: "reload")
        XCTAssertNotEqual(Int(pid), original, "Must be a new runner process, not object recreation")
        print("C1 encrypted pending + Keychain decrypt survived; seed PID=\(original), reload PID=\(pid)")
    }

    func testCleanup() throws {
        _ = try NativeAuthPersistenceFixtureKt.runNativeAuthPersistenceFixture(phase: "cleanup")
        UserDefaults.standard.removeObject(forKey: "c1_fixture_seed_pid")
    }
}
