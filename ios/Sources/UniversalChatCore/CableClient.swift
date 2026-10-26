import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The conversation's ActionCable stream: agent replies, read receipts and the
/// conversation closing. Reconnects with backoff, and treats a server that has
/// stopped pinging as gone.
///
/// A rejected subscription means the token lapsed; `onRejected` is asked for a
/// fresh one. Callbacks arrive on the main actor.
@MainActor
public final class CableClient {
    public static let channel = "UniversalInbox::WebchatChannel"
    /// Rails pings every 3 seconds.
    static let staleAfter: TimeInterval = 10

    private let origin: String
    private let url: URL
    private let session: URLSession
    private let onEvent: @MainActor (CableEvent) -> Void
    private let onRejected: @MainActor () -> Void

    private var task: URLSessionWebSocketTask?
    private var token: String?
    private var attempts = 0
    private var stopped = true
    private var reconnect: Task<Void, Never>?
    private var watchdog: Task<Void, Never>?

    public var connected: Bool { task != nil && !stopped }

    public init(serverOrigin: String, session: URLSession = .shared,
                onEvent: @escaping @MainActor (CableEvent) -> Void, onRejected: @escaping @MainActor () -> Void) {
        origin = serverOrigin
        let socketOrigin = serverOrigin.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://")
        url = URL(string: socketOrigin + "/cable")!
        self.session = session
        self.onEvent = onEvent
        self.onRejected = onRejected
    }

    /// Subscribes with the token, replacing any earlier subscription.
    public func subscribe(_ cableToken: String) {
        if cableToken == token && connected { return }
        stop()
        token = cableToken
        stopped = false
        attempts = 0
        connect()
    }

    public func stop() {
        stopped = true
        reconnect?.cancel()
        watchdog?.cancel()
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func connect() {
        // Rails checks the Origin of a cable connection against the app's own
        // host; a browser sends it for free, a native socket has to say it.
        var request = URLRequest(url: url)
        request.setValue(origin, forHTTPHeaderField: "Origin")
        let socket = session.webSocketTask(with: request)
        task = socket
        socket.resume()
        receive(on: socket)
    }

    private func receive(on socket: URLSessionWebSocketTask) {
        Task { [weak self] in
            while true {
                do {
                    let message = try await socket.receive()
                    guard let self, socket === self.task else { return }
                    if case .string(let text) = message { self.handle(text, on: socket) }
                } catch {
                    guard let self, socket === self.task else { return }
                    self.scheduleReconnect()
                    return
                }
            }
        }
    }

    private func handle(_ text: String, on socket: URLSessionWebSocketTask) {
        guard let frame = JSON.parse(Data(text.utf8)) else { return }
        switch frame["type"].string {
        case "welcome":
            attempts = 0
            armWatchdog()
            let identifier = JSON.object([("channel", .string(Self.channel)), ("cable_token", .string(token ?? ""))]).serialized
            let subscribe = JSON.object([("command", .string("subscribe")), ("identifier", .string(identifier))]).serialized
            Task { try? await socket.send(.string(subscribe)) }
        case "ping":
            armWatchdog()
        case "reject_subscription":
            stop()
            onRejected()
        case "disconnect":
            if frame["reconnect"].bool { scheduleReconnect() }
        case nil:
            if let event = Self.parseEvent(frame["message"]) { onEvent(event) }
        default:
            break
        }
    }

    private func scheduleReconnect() {
        guard !stopped else { return }
        task?.cancel()
        task = nil
        watchdog?.cancel()
        reconnect?.cancel()
        let wait = Self.backoff(attempts)
        attempts += 1
        reconnect = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard let self, !Task.isCancelled, !self.stopped else { return }
            self.connect()
        }
    }

    private func armWatchdog() {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.staleAfter * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.scheduleReconnect()
        }
    }

    nonisolated static func backoff(_ attempt: Int) -> TimeInterval {
        min(30, pow(2, Double(min(attempt, 5))))
    }

    nonisolated static func parseEvent(_ message: JSON) -> CableEvent? {
        let data = message["data"]
        switch message["event"].string {
        case "agent_message":
            guard let id = data["platform_message_id"].string else { return nil }
            let text = data["body"].string ?? ""
            let attachments = parseAttachments(data["attachments"])
            if text.isEmpty && attachments.isEmpty { return nil }
            return .agentMessage(ChatMessage(id: id, author: .agent, text: text, authorName: data["author_name"].string, sentAt: data["sent_at"].string,
                                             platformId: id, attachments: attachments))
        case "conversation_closed":
            return .conversationClosed(conversationId: data["conversation_id"].int, csatOffered: data["csat_offered"].bool)
        default:
            return nil
        }
    }
}
