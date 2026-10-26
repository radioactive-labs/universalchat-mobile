import Foundation

/// A custom attribute's value. Flat scalars only; `.null` clears one set earlier.
public enum AttributeValue: Equatable, Sendable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByBooleanLiteral, ExpressibleByNilLiteral {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(nilLiteral: ()) { self = .null }

    var json: JSON {
        switch self {
        case .string(let s): return .string(s)
        case .number(let n): return .number(n)
        case .bool(let b): return .bool(b)
        case .null: return .null
        }
    }
}

/// Something the bot offers to answer with. The label is what gets sent back.
public struct Choice: Equatable, Hashable, Sendable {
    public let key: String
    public let label: String
}

/// A way into the flow, offered before a conversation exists.
public struct Entrypoint: Equatable, Hashable, Sendable {
    public let id: String
    public let label: String
}

public struct Media: Equatable, Sendable {
    public let url: URL
    public let type: String
    public let caption: String?
}

/// A file on a message: one the visitor sent, or one already stored. `url` is
/// the signed address to fetch it from, or a local file while the visitor's own
/// copy is still uploading. `kind` is image, video, audio or document.
public struct Attachment: Equatable, Sendable {
    public let url: URL
    public let kind: String
    public let filename: String?
    public let mimeType: String?

    public init(url: URL, kind: String, filename: String?, mimeType: String?) {
        self.url = url
        self.kind = kind
        self.filename = filename
        self.mimeType = mimeType
    }
}

/// A file the visitor picked to send. `previewURL` is shown in their bubble while it uploads.
public struct Upload: Equatable, Sendable {
    public let filename: String
    public let mimeType: String
    public let data: Data
    public let previewURL: URL?

    public init(filename: String, mimeType: String, data: Data, previewURL: URL?) {
        self.filename = filename
        self.mimeType = mimeType
        self.data = data
        self.previewURL = previewURL
    }

    public var kind: String { Uploads.kind(of: mimeType) }
}

/// What the server will take, kept in step with the website widget's file picker.
public enum Uploads {
    public static let maxMB = 25
    public static let maxBytes = maxMB * 1024 * 1024
    public static let acceptedTypes = [
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "application/pdf",
        "audio/ogg", "audio/mpeg", "audio/mp4", "audio/amr",
        "video/mp4", "video/3gpp",
        "text/plain",
    ]

    public static func kind(of mimeType: String?) -> String {
        guard let mimeType else { return "document" }
        if mimeType.hasPrefix("image/") { return "image" }
        if mimeType.hasPrefix("video/") { return "video" }
        if mimeType.hasPrefix("audio/") { return "audio" }
        return "document"
    }

    public static let tooLarge = "That file is too large. The limit is \(maxMB) MB."

    /// Why the server would refuse it, worded for the visitor, or nil when it will take it.
    public static func refusal(_ upload: Upload) -> String? {
        if upload.data.count > maxBytes { return tooLarge }
        if !acceptedTypes.contains(upload.mimeType) { return "That type of file can't be sent here." }
        return nil
    }

    public enum ReadError: Error, Equatable {
        case tooLarge
        case unreadable
    }

    /// Reads a picked file, refusing one over the limit before its bytes are
    /// loaded, so a long video cannot use up the app's memory. A file whose
    /// size is not reported is read only until it passes the limit.
    public static func read(fileAt url: URL) throws -> Data {
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > maxBytes {
            throw ReadError.tooLarge
        }
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw ReadError.unreadable }
        defer { try? handle.close() }
        let data: Data
        do {
            // Nil at the end of the file, which for an empty one is at once.
            data = try handle.read(upToCount: maxBytes + 1) ?? Data()
        } catch {
            throw ReadError.unreadable
        }
        if data.count > maxBytes { throw ReadError.tooLarge }
        return data
    }
}

/// A stored upload, named by the reference the next turn claims it with.
public struct MediaRef: Equatable, Sendable {
    public let ref: String
    public let mimeType: String
    public let mediaType: String
}

public enum Author: Equatable, Sendable { case user, bot, agent }

public enum Delivery: Equatable, Sendable { case sending, sent, failed }

public struct ChatMessage: Equatable, Identifiable, Sendable {
    public let id: String
    public let author: Author
    public let text: String
    public var media: Media?
    public var authorName: String?
    /// ISO 8601, as the server sends it. Nil while a message is still sending.
    public var sentAt: String?
    public var delivery: Delivery = .sent
    public var error: String?
    /// The server's id, for paging back from this message.
    public var historyId: Int?
    /// The id an agent reply is sent live under, which its history copy
    /// carries too, so the two can be told to be the same message. Nil for
    /// the visitor's own messages and the bot's.
    public var platformId: String?
    /// The files on the message, in the order they were sent.
    public var attachments: [Attachment]
    /// The file a failed send was carrying, so retrying sends it again.
    public var pendingUpload: Upload?

    public init(id: String, author: Author, text: String, media: Media? = nil, authorName: String? = nil, sentAt: String? = nil,
                delivery: Delivery = .sent, error: String? = nil, historyId: Int? = nil, platformId: String? = nil, attachments: [Attachment] = [],
                pendingUpload: Upload? = nil) {
        self.id = id
        self.author = author
        self.text = text
        self.media = media
        self.authorName = authorName
        self.sentAt = sentAt
        self.delivery = delivery
        self.error = error
        self.historyId = historyId
        self.platformId = platformId
        self.attachments = attachments
        self.pendingUpload = pendingUpload
    }
}

public struct CsatSettings: Equatable, Sendable {
    public let enabled: Bool
    public let question: String?
    public let thankYou: String?
}

/// The deployment's chat settings: the widget customizer's values, the ways in, and CSAT.
public struct ChatConfig: Equatable, Sendable {
    public var style: WidgetStyle
    public var greeting: String?
    public var collectUserInfo: String?
    public var userInfoFields: [String]
    public var userInfoRequiredFields: [String]
    public var entrypoints: [Entrypoint]
    public var csat: CsatSettings

    public static let empty = ChatConfig(style: .default, greeting: nil, collectUserInfo: nil,
                                         userInfoFields: [], userInfoRequiredFields: [], entrypoints: [],
                                         csat: CsatSettings(enabled: false, question: nil, thankYou: nil))

    static func parse(_ body: JSON) -> ChatConfig {
        let widget = body["chatWidget"]
        let csat = body["csat"]
        let fields = widget["userInfoFields"].csv
        let collect = widget["collectUserInfo"].string
        return ChatConfig(
            style: .parse(widget, fontStylesheet: body["fontStylesheet"].string),
            greeting: widget["greeting"].string,
            collectUserInfo: collect == "never" ? nil : collect,
            userInfoFields: fields.isEmpty ? ["name", "email"] : fields,
            userInfoRequiredFields: widget["userInfoRequiredFields"].csv,
            entrypoints: body["entrypoints"].array.compactMap { entry in
                guard let id = entry["id"].string, let label = entry["label"].string else { return nil }
                return Entrypoint(id: id, label: label)
            },
            csat: CsatSettings(enabled: csat["enabled"].bool, question: csat["question"].string, thankYou: csat["thankYou"].string)
        )
    }
}

/// One turn's answer from the processor.
public enum TurnResult: Equatable, Sendable {
    case reply(terminal: Bool, message: String?, choices: [Choice], media: Media?)
    /// A person has taken over; their reply arrives on the cable.
    case skipped
}

public struct History: Equatable, Sendable {
    public var conversationId: Int?
    public var status: String?
    public var messages: [ChatMessage]
    public var lastChoices: [Choice]
    public var hasOlder: Bool
    public var cableToken: String?
    public var csatOffered: Bool
    public var signedIn: Bool = false
    /// A signed-in visitor has an earlier conversation to look back on.
    public var pastConversations: Bool = false
}

/// One of a signed-in visitor's conversations, as their list shows it.
public struct ConversationSummary: Equatable, Identifiable, Sendable {
    public var id: Int
    public var ended: Bool
    public var startedAt: String?
    public var lastAuthor: Author
    public var lastAuthorName: String?
    public var lastText: String
    public var lastHasAttachment: Bool
    public var lastSentAt: String?

    public init(id: Int, ended: Bool, startedAt: String?, lastAuthor: Author, lastAuthorName: String?, lastText: String, lastHasAttachment: Bool, lastSentAt: String?) {
        self.id = id
        self.ended = ended
        self.startedAt = startedAt
        self.lastAuthor = lastAuthor
        self.lastAuthorName = lastAuthorName
        self.lastText = lastText
        self.lastHasAttachment = lastHasAttachment
        self.lastSentAt = lastSentAt
    }
}

public struct ConversationPage: Equatable, Sendable {
    public var conversations: [ConversationSummary]
    public var hasMore: Bool
}

func parseConversationSummary(_ json: JSON) -> ConversationSummary? {
    guard let id = json["id"].int else { return nil }
    let last = json["last_message"]
    let author: Author
    switch last["role"].string {
    case "user": author = .user
    case "agent": author = .agent
    default: author = .bot
    }
    return ConversationSummary(
        id: id,
        ended: json["ended"].bool,
        startedAt: json["started_at"].string,
        lastAuthor: author,
        lastAuthorName: last["author_name"].string,
        lastText: last["text"].string ?? "",
        lastHasAttachment: last["has_attachment"].bool,
        lastSentAt: last["sent_at"].string
    )
}

/// An event on the conversation's cable stream.
public enum CableEvent: Equatable, Sendable {
    case agentMessage(ChatMessage)
    case conversationClosed(conversationId: Int?, csatOffered: Bool)
}

/// Choices arrive as an object of key to label, where a label can itself be an
/// object carrying `value` or `label`, or as a list of `{key, value}`.
func parseChoices(_ json: JSON) -> [Choice] {
    switch json {
    case .object:
        return orderedChoices(json)
    case .array(let items):
        return items.compactMap { item in
            guard let label = choiceLabel(item["value"]) ?? choiceLabel(item["label"]) else { return nil }
            return Choice(key: item["key"].string ?? label, label: label)
        }
    default:
        return []
    }
}

private func orderedChoices(_ json: JSON) -> [Choice] {
    json.orderedEntries.compactMap { key, value in choiceLabel(value).map { Choice(key: key, label: $0) } }
}

private func choiceLabel(_ value: JSON) -> String? {
    switch value {
    case .string(let s): return s
    case .number: return value.string
    case .object: return value["value"].string ?? value["label"].string
    default: return nil
    }
}

/// Media arrives as `{url, type, caption}`, or as a bare URL.
func parseMedia(_ json: JSON) -> Media? {
    switch json {
    case .object:
        guard let url = json["url"].string.flatMap(URL.init(string:)) else { return nil }
        return Media(url: url, type: json["type"].string ?? "image", caption: json["caption"].string)
    case .string(let s):
        return URL(string: s).map { Media(url: $0, type: "image", caption: nil) }
    default:
        return nil
    }
}

func parseHistoryMessage(_ json: JSON) -> ChatMessage? {
    let author: Author
    switch json["role"].string {
    case "user": author = .user
    case "agent": author = .agent
    case "bot": author = .bot
    default: return nil
    }
    let media = parseMedia(json["media"])
    let text = json["text"].string ?? ""
    let attachments = parseAttachments(json["attachments"])
    if text.isEmpty && media == nil && attachments.isEmpty { return nil }
    let id = json["id"].int
    return ChatMessage(id: "history-\(id.map(String.init) ?? json["sent_at"].string ?? UUID().uuidString)", author: author, text: text, media: media,
                       authorName: json["author_name"].string, sentAt: json["sent_at"].string, historyId: id,
                       platformId: json["platform_message_id"].string, attachments: attachments)
}

/// The files on a message, live or from history: `{url, filename, kind,
/// content_type}` each. One with no url never arrived and is left out.
func parseAttachments(_ json: JSON) -> [Attachment] {
    json.array.compactMap { file in
        file["url"].string.flatMap(URL.init(string:)).map {
            Attachment(url: $0, kind: file["kind"].string ?? Uploads.kind(of: file["content_type"].string), filename: file["filename"].string, mimeType: file["content_type"].string)
        }
    }
}
