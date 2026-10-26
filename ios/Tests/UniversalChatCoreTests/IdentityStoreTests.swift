import XCTest
@testable import UniversalChatCore

final class IdentityStoreTests: XCTestCase {
    private final class FakeSecrets: SecretStore {
        var values: [String: String] = [:]
        var refuses = false

        func secret(_ key: String) -> String? { values[key] }

        func setSecret(_ key: String, _ value: String?) -> Bool {
            if refuses { return false }
            values[key] = value
            return true
        }
    }

    private var defaults: UserDefaults!
    private let suite = "identity-store-tests"

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    func testVisitorTokensAreKeptAsSecretsAndTheRestInDefaults() {
        let secrets = FakeSecrets()
        let store = UserDefaultsIdentityStore(deploymentID: "7", defaults: defaults, secrets: secrets)

        store.signedInVisitor = "v-signed"
        store.signedInUserID = "u-1"

        XCTAssertEqual(secrets.values["universalchat.7.signedInVisitor"], "v-signed")
        XCTAssertNil(defaults.string(forKey: "universalchat.7.signedInVisitor"))
        XCTAssertEqual(defaults.string(forKey: "universalchat.7.signedInUserID"), "u-1")
        XCTAssertEqual(store.signedInVisitor, "v-signed")
    }

    func testATokenAnEarlierVersionLeftInDefaultsMovesToSecrets() {
        defaults.set("v-old", forKey: "universalchat.7.anonymousVisitor")
        defaults.set(true, forKey: "universalchat.7.installed")
        let secrets = FakeSecrets()
        let store = UserDefaultsIdentityStore(deploymentID: "7", defaults: defaults, secrets: secrets)

        XCTAssertEqual(store.anonymousVisitor, "v-old")
        XCTAssertEqual(secrets.values["universalchat.7.anonymousVisitor"], "v-old")
        XCTAssertNil(defaults.string(forKey: "universalchat.7.anonymousVisitor"))
    }

    func testAFreshInstallDropsAVisitorAnEarlierInstallLeftInSecrets() {
        let secrets = FakeSecrets()
        secrets.values["universalchat.7.signedInVisitor"] = "v-left-behind"

        let store = UserDefaultsIdentityStore(deploymentID: "7", defaults: defaults, secrets: secrets)

        XCTAssertNil(store.signedInVisitor)
    }

    func testATokenStaysInDefaultsWhenSecretsRefuseIt() {
        let secrets = FakeSecrets()
        let store = UserDefaultsIdentityStore(deploymentID: "7", defaults: defaults, secrets: secrets)
        secrets.refuses = true

        store.anonymousVisitor = "v-kept"

        XCTAssertEqual(store.anonymousVisitor, "v-kept")
    }
}
