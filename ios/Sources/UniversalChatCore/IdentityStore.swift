import Foundation
#if canImport(Security)
import Security
#endif

/// Where the SDK keeps who the visitor is between launches. A signed-in user's
/// visitor is kept apart from the anonymous one, so signing out falls back to
/// the anonymous conversation rather than ending up in nobody's.
public protocol IdentityStore: AnyObject {
    var anonymousVisitor: String? { get set }
    var signedInUserID: String? { get set }
    var signedInVisitor: String? { get set }
    var pushToken: String? { get set }
    /// The visitor the push token was last registered under, so it can be moved.
    var pushVisitor: String? { get set }
    var userInfoCollected: Bool { get set }
    /// The visitor's own light or dark choice from the header toggle, overriding the deployment's.
    var themeOverride: String? { get set }
}

public final class InMemoryIdentityStore: IdentityStore {
    public var anonymousVisitor: String?
    public var signedInUserID: String?
    public var signedInVisitor: String?
    public var pushToken: String?
    public var pushVisitor: String?
    public var userInfoCollected = false
    public var themeOverride: String?

    public init() {}
}

/// Where the visitor tokens are kept. Each one is the visitor to anything
/// holding it, so it lives apart from the rest of the identity.
public protocol SecretStore: AnyObject {
    func secret(_ key: String) -> String?
    /// Whether the value was stored. Nil removes it.
    @discardableResult func setSecret(_ key: String, _ value: String?) -> Bool
}

/// The identity kept in UserDefaults, one set of keys per deployment, so two
/// deployments in one app never share a visitor. The visitor tokens go to
/// `secrets` when given one, and a token an earlier version left in
/// UserDefaults moves there the first time it is read.
public final class UserDefaultsIdentityStore: IdentityStore {
    private let defaults: UserDefaults
    private let prefix: String
    private let secrets: SecretStore?

    public init(deploymentID: String, defaults: UserDefaults = .standard, secrets: SecretStore? = nil) {
        self.defaults = defaults
        self.secrets = secrets
        prefix = "universalchat.\(deploymentID)."
        // Secrets outlive the app on iOS, while UserDefaults do not. Without
        // the marker this is a fresh install, and a visitor left behind by an
        // earlier one is not this install's.
        if let secrets, !defaults.bool(forKey: prefix + "installed") {
            for key in Self.secretKeys { secrets.setSecret(prefix + key, nil) }
            defaults.set(true, forKey: prefix + "installed")
        }
    }

    private static let secretKeys = ["anonymousVisitor", "signedInVisitor", "pushVisitor"]

    private func value(_ key: String) -> String? {
        guard let secrets, Self.secretKeys.contains(key) else { return defaults.string(forKey: prefix + key) }
        if let secret = secrets.secret(prefix + key) { return secret }
        guard let plain = defaults.string(forKey: prefix + key) else { return nil }
        if secrets.setSecret(prefix + key, plain) { defaults.removeObject(forKey: prefix + key) }
        return plain
    }

    private func set(_ key: String, _ value: String?) {
        if let secrets, Self.secretKeys.contains(key), secrets.setSecret(prefix + key, value) {
            defaults.removeObject(forKey: prefix + key)
            return
        }
        if let value { defaults.set(value, forKey: prefix + key) } else { defaults.removeObject(forKey: prefix + key) }
    }

    public var anonymousVisitor: String? { get { value("anonymousVisitor") } set { set("anonymousVisitor", newValue) } }
    public var signedInUserID: String? { get { value("signedInUserID") } set { set("signedInUserID", newValue) } }
    public var signedInVisitor: String? { get { value("signedInVisitor") } set { set("signedInVisitor", newValue) } }
    public var pushToken: String? { get { value("pushToken") } set { set("pushToken", newValue) } }
    public var pushVisitor: String? { get { value("pushVisitor") } set { set("pushVisitor", newValue) } }
    public var themeOverride: String? { get { value("themeOverride") } set { set("themeOverride", newValue) } }
    public var userInfoCollected: Bool {
        get { defaults.bool(forKey: prefix + "userInfoCollected") }
        set { defaults.set(newValue, forKey: prefix + "userInfoCollected") }
    }
}

#if canImport(Security)
/// Secrets in the Keychain, readable once the device has been unlocked after
/// a restart, so a push handled in the background can read them, and never
/// copied to a backup or another device.
public final class KeychainSecretStore: SecretStore {
    private let service: String

    public init(service: String = "com.universalchatbot.chat") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }

    public func secret(_ key: String) -> String? {
        var item: CFTypeRef?
        var search = query(key)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        guard SecItemCopyMatching(search as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    public func setSecret(_ key: String, _ value: String?) -> Bool {
        guard let value else {
            let status = SecItemDelete(query(key) as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query(key) as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        return SecItemAdd(query(key).merging(attributes) { $1 } as CFDictionary, nil) == errSecSuccess
    }
}
#endif
