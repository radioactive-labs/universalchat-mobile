import Foundation
import React
import UIKit
import UniversalChat

/// Passes the JavaScript API's calls through to the UniversalChat SDK.
@objc(UniversalChatModule)
final class UniversalChatModule: RCTEventEmitter {
    private static let linkEvent = "UniversalChatLink"

    @objc override static func requiresMainQueueSetup() -> Bool { false }

    override func supportedEvents() -> [String] { [Self.linkEvent] }

    @objc(setLinkPrefixes:resolve:reject:)
    func setLinkPrefixes(_ prefixes: [String], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in
            // The chat asks while the visitor's tap is still in hand, so the
            // answer is decided here and JavaScript hears about the link after.
            UniversalChat.linkHandler = prefixes.isEmpty ? nil : { [weak self] url in
                let ours = prefixes.contains { url.absoluteString.hasPrefix($0) }
                if ours { self?.sendEvent(withName: Self.linkEvent, body: ["url": url.absoluteString]) }
                return ours
            }
            resolve(nil)
        }
    }

    @objc(configure:deploymentId:resolve:reject:)
    func configure(_ serverUrl: String, deploymentId: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let url = URL(string: serverUrl) else { return reject("invalid_arguments", "serverUrl is not a URL", nil) }
        Task { @MainActor in
            UniversalChat.configure(serverURL: url, deploymentID: deploymentId)
            resolve(nil)
        }
    }

    @objc(present:reject:)
    func present(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in
            guard let top = RCTPresentedViewController() else { return reject("no_view_controller", "Nothing on screen to present the chat from", nil) }
            UniversalChat.present(from: top)
            resolve(nil)
        }
    }

    @objc(login:resolve:reject:)
    func login(_ userToken: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in
            do {
                resolve(try await UniversalChat.login(userToken: userToken))
            } catch let error as APIError {
                reject(error.code ?? "login_failed", error.message, error)
            } catch {
                reject("login_failed", error.localizedDescription, error)
            }
        }
    }

    @objc(logout:reject:)
    func logout(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in
            UniversalChat.logout()
            resolve(nil)
        }
    }

    @objc(setAttributes:resolve:reject:)
    func setAttributes(_ attributes: [String: Any], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        let values = attributes.mapValues(Self.attributeValue)
        Task { @MainActor in
            UniversalChat.setAttributes(values)
            resolve(nil)
        }
    }

    @objc(setPushToken:resolve:reject:)
    func setPushToken(_ token: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in
            UniversalChat.setPushToken(token)
            resolve(nil)
        }
    }

    @objc(isUniversalChatPush:resolve:reject:)
    func isUniversalChatPush(_ data: [String: Any], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(UniversalChat.isUniversalChatNotification(data))
    }

    // iOS shows the notification itself; whether it shows while the app is
    // open is shouldPresentNotification's answer.
    @objc(handlePush:title:body:resolve:reject:)
    func handlePush(_ data: [String: Any], title: String?, body: String?, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        resolve(UniversalChat.isUniversalChatNotification(data))
    }

    @objc(shouldPresentNotification:resolve:reject:)
    func shouldPresentNotification(_ data: [String: Any], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in resolve(UniversalChat.shouldPresentNotification(data)) }
    }

    @objc(handleNotificationTap:resolve:reject:)
    func handleNotificationTap(_ data: [String: Any], resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        Task { @MainActor in resolve(UniversalChat.handleNotification(data, presentingFrom: RCTPresentedViewController())) }
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
}
