import Flutter
import UIKit
import UniversalChat

/// Passes the Dart API's calls through to the UniversalChat SDK.
public final class UniversalChatPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "universal_chat", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(UniversalChatPlugin(channel: channel), channel: channel)
    }

    private let channel: FlutterMethodChannel

    init(channel: FlutterMethodChannel) {
        self.channel = channel
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        Task { @MainActor in
            switch call.method {
            case "configure":
                guard let server = (args["serverUrl"] as? String).flatMap(URL.init(string:)), let deployment = args["deploymentId"] as? String else {
                    return result(FlutterError(code: "invalid_arguments", message: "serverUrl and deploymentId are required", details: nil))
                }
                UniversalChat.configure(serverURL: server, deploymentID: deployment)
                result(nil)
            case "present":
                guard let root = Self.topViewController() else {
                    return result(FlutterError(code: "no_view_controller", message: "Nothing on screen to present the chat from", details: nil))
                }
                UniversalChat.present(from: root)
                result(nil)
            case "login":
                do {
                    result(try await UniversalChat.login(userToken: args["userToken"] as? String ?? ""))
                } catch let error as APIError {
                    result(FlutterError(code: error.code ?? "login_failed", message: error.message, details: nil))
                } catch {
                    result(FlutterError(code: "login_failed", message: error.localizedDescription, details: nil))
                }
            case "logout":
                UniversalChat.logout()
                result(nil)
            case "setAttributes":
                UniversalChat.setAttributes((args["attributes"] as? [String: Any] ?? [:]).mapValues(Self.attributeValue))
                result(nil)
            case "setPushToken":
                if let token = args["token"] as? String { UniversalChat.setPushToken(token) }
                result(nil)
            case "isUniversalChatPush":
                result(UniversalChat.isUniversalChatNotification(args["data"] as? [String: Any] ?? [:]))
            case "handlePush":
                // iOS shows the notification itself; what is left is whether it
                // should show while the app is open, which the app decides
                // with shouldPresentNotification.
                result(UniversalChat.isUniversalChatNotification(args["data"] as? [String: Any] ?? [:]))
            case "shouldPresentNotification":
                result(UniversalChat.shouldPresentNotification(args["data"] as? [String: Any] ?? [:]))
            case "setLinkPrefixes":
                let prefixes = args["prefixes"] as? [String] ?? []
                // The chat asks while the visitor's tap is still in hand, so the
                // answer is decided here and Dart hears about the link after.
                UniversalChat.linkHandler = prefixes.isEmpty ? nil : { [channel] url in
                    let ours = prefixes.contains { url.absoluteString.hasPrefix($0) }
                    if ours { channel.invokeMethod("openLink", arguments: url.absoluteString) }
                    return ours
                }
                result(nil)
            case "handleNotificationTap":
                result(UniversalChat.handleNotification(args["data"] as? [String: Any] ?? [:], presentingFrom: Self.topViewController()))
            default:
                result(FlutterMethodNotImplemented)
            }
        }
    }

    private static func attributeValue(_ value: Any) -> AttributeValue {
        switch value {
        case let number as NSNumber where CFGetTypeID(number) == CFBooleanGetTypeID(): return .bool(number.boolValue)
        case let number as NSNumber: return .number(number.doubleValue)
        case let text as String: return .string(text)
        case is NSNull: return .null
        default: return .string(String(describing: value))
        }
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive } as? UIWindowScene
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
