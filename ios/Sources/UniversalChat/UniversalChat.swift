import Foundation
@_exported import UniversalChatCore
#if canImport(UIKit)
import UIKit
#endif

/// The Universal Chatbot chat for one mobile deployment.
///
/// ```swift
/// UniversalChat.configure(serverURL: URL(string: "https://acme.universalchatbot.com")!, deploymentID: "42")
/// UniversalChat.present(from: viewController)
/// ```
///
/// Sign a user in with a token your server signs (`login(userToken:)`); without
/// one the visitor is anonymous. Pass the FCM token to `setPushToken(_:)` and
/// incoming notifications to `handleNotification(_:)` to tell users when an
/// agent replies.
@MainActor
public enum UniversalChat {
    private static var deploymentID: String?
    private static var identity: IdentityStore?
    private static var api: APIClient?
    static private(set) var controller: ChatController?

    /// Whether the chat screen is on screen, so a notification for it is not shown twice.
    static var chatVisible = false

    public static var isConfigured: Bool { controller != nil }

    /// The signed-in user's id, or nil for an anonymous visitor.
    public static var userID: String? { identity?.signedInUserID }

    /// Call once at launch. `serverURL` and `deploymentID` are shown in the
    /// deployment's App setup panel.
    public static func configure(serverURL: URL, deploymentID: String) {
        let identity = UserDefaultsIdentityStore(deploymentID: deploymentID, secrets: KeychainSecretStore())
        let api = APIClient(serverURL: serverURL, deploymentID: deploymentID)
        self.deploymentID = deploymentID
        self.identity = identity
        self.api = api
        controller = ChatController(api: api, identity: identity) { onEvent, onRejected in
            CableClient(serverOrigin: api.serverOrigin, onEvent: onEvent, onRejected: onRejected)
        }
    }

    /// Signs in the user `userToken` names: a JWT your server signs with the
    /// deployment's identity secret. Their conversation follows them to any
    /// device they sign in on, and agents see them as verified.
    ///
    /// Returns the user's id, and throws when the token does not verify.
    @discardableResult
    public static func login(userToken: String) async throws -> String {
        guard let api, let identity else { throw APIError(status: 0, code: "not_configured", message: "Call UniversalChat.configure first") }
        let (visitor, userID) = try await api.signedInVisitor(userToken: userToken)
        let changedUser = identity.signedInUserID != userID
        identity.signedInVisitor = visitor
        identity.signedInUserID = userID
        await moveDevice()
        if changedUser { restartChat() }
        return userID
    }

    /// Signs the user out. Their device stops getting their notifications, and
    /// the chat goes back to an anonymous conversation.
    public static func logout() {
        guard let identity, identity.signedInVisitor != nil else { return }
        identity.signedInVisitor = nil
        identity.signedInUserID = nil
        identity.userInfoCollected = false
        // Registering the token again moves it off the user, to the anonymous visitor.
        Task { await moveDevice() }
        restartChat()
    }

    /// Custom attributes about the visitor, such as their plan or the screen
    /// they opened the chat from. Flows read them as `{{@visitor.attributes.plan}}`
    /// and agents see them beside the conversation. Merged with what was set
    /// before; set a key to `nil` to clear it.
    ///
    /// These come from the device, so a flow should not trust them for anything
    /// that matters. Put those in the user token's `attributes` claim instead.
    public static func setAttributes(_ attributes: [String: AttributeValue]) {
        controller?.attributes.merge(attributes) { _, new in new }
    }

    /// Pass the FCM registration token, from MessagingDelegate's
    /// didReceiveRegistrationToken, at every launch.
    public static func setPushToken(_ token: String) {
        guard let identity else { return }
        if identity.pushToken == token, identity.pushVisitor == currentVisitor { return }
        identity.pushToken = token
        identity.pushVisitor = nil
        Task { await moveDevice() }
    }

    /// Whether a notification's payload is one of ours.
    public nonisolated static func isUniversalChatNotification(_ userInfo: [AnyHashable: Any]) -> Bool {
        (userInfo["ucb"] as? String) == "1"
    }

    /// Whether a notification arriving while the app is open should be shown:
    /// not when the chat it is about is already on screen. Use from
    /// `userNotificationCenter(_:willPresent:withCompletionHandler:)`.
    public static func shouldPresentNotification(_ userInfo: [AnyHashable: Any]) -> Bool {
        !(isUniversalChatNotification(userInfo) && chatVisible)
    }

    /// Decides what happens to a link tapped in the chat, such as one a flow
    /// sends to a screen of your app. Return true when your app handled it, and
    /// the chat closes so the visitor lands there; false to open it as usual.
    public static var linkHandler: ((URL) -> Bool)?

    /// The visitor's light or dark choice from the header toggle, kept across launches.
    static var themeOverride: String? {
        get { identity?.themeOverride }
        set { identity?.themeOverride = newValue }
    }

    private static var currentVisitor: String? { identity?.signedInVisitor ?? identity?.anonymousVisitor }

    /// Registers the push token under whoever is chatting now.
    private static func moveDevice() async {
        guard let api, let identity, let token = identity.pushToken else { return }
        var visitor = currentVisitor
        if visitor == nil {
            visitor = try? await api.anonymousVisitor()
            identity.anonymousVisitor = visitor
        }
        guard let visitor, identity.pushVisitor != visitor else { return }
        if (try? await api.registerDevice(visitor: visitor, token: token)) != nil {
            identity.pushVisitor = visitor
        }
    }

    private static func restartChat() {
        controller?.reset()
        if chatVisible { controller?.open() }
    }

    #if canImport(UIKit)
    /// Opens the chat full screen over `viewController`.
    public static func present(from viewController: UIViewController, animated: Bool = true) {
        guard controller != nil else { return }
        let chat = ChatViewController()
        chat.modalPresentationStyle = .fullScreen
        viewController.present(chat, animated: animated)
    }

    /// Opens the chat when the user tapped one of our notifications. Use from
    /// `userNotificationCenter(_:didReceive:withCompletionHandler:)`. Returns
    /// false for a notification that is not ours.
    /// The chat opens on the conversation the notification was about.
    @discardableResult
    public static func handleNotification(_ userInfo: [AnyHashable: Any], presentingFrom viewController: UIViewController?) -> Bool {
        guard isUniversalChatNotification(userInfo) else { return false }
        if let id = (userInfo["ucb_conversation_id"] as? String).flatMap(Int.init) { controller?.openConversation(id) }
        if !chatVisible, let viewController { present(from: viewController) }
        return true
    }
    #endif
}
