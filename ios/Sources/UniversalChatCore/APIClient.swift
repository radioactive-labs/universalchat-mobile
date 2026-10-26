import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A request the server answered with an error, worded for the visitor where the server words it.
public struct APIError: Error, Equatable, LocalizedError {
    public let status: Int
    public let code: String?
    public let message: String

    public var errorDescription: String? { message }

    public init(status: Int, code: String?, message: String) {
        self.status = status
        self.code = code
        self.message = message
    }
}

public struct TurnResponse: Equatable, Sendable {
    public let result: TurnResult
    public let replacementVisitor: String?
}

/// The deployment's public endpoints. See sdks/README.md for the protocol.
public final class APIClient: @unchecked Sendable {
    public static let replacementVisitorHeader = "X-Webchat-Visitor"

    public let serverURL: URL
    private let deploymentID: String
    private let session: URLSession

    public init(serverURL: URL, deploymentID: String, session: URLSession = .shared) {
        self.serverURL = serverURL
        self.deploymentID = deploymentID
        self.session = session
    }

    /// The origin the cable must name, scheme and host only.
    public var serverOrigin: String {
        var components = URLComponents(url: serverURL, resolvingAgainstBaseURL: false)!
        components.path = ""
        components.query = nil
        return components.string!.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    /// Nil when the deployment is not answering.
    public func config() async throws -> ChatConfig? {
        let (data, response) = try await send(request("config"))
        if response.statusCode == 404 { return nil }
        return ChatConfig.parse(try checked(data, response))
    }

    public func anonymousVisitor() async throws -> String {
        let body = try await post("visitor", .object([]))
        guard let visitor = body["visitor"].string else { throw APIError(status: 200, code: nil, message: "No visitor in response") }
        return visitor
    }

    /// The signed-in user's visitor and id, exchanged for the token the customer's server signed.
    public func signedInVisitor(userToken: String) async throws -> (visitor: String, userID: String) {
        let body = try await post("visitor", .object([("user_token", .string(userToken))]))
        guard let visitor = body["visitor"].string else { throw APIError(status: 200, code: nil, message: "No visitor in response") }
        return (visitor, body["user_id"].string ?? "")
    }

    public func turn(visitor: String?, input: String, userInfo: [String: String]? = nil, attributes: [String: AttributeValue] = [:],
                     media: MediaRef? = nil) async throws -> TurnResponse {
        var payload: [(String, JSON)] = [("input", .string(input))]
        if let visitor { payload.append(("visitor", .string(visitor))) }
        // The file this turn sends, claimed by the reference its upload got.
        if let media {
            payload.append(contentsOf: [("media_url", .string(media.ref)), ("media_type", .string(media.mediaType)), ("mime_type", .string(media.mimeType))])
        }
        if !attributes.isEmpty {
            payload.append(("attributes", .object(attributes.sorted { $0.key < $1.key }.map { ($0.key, $0.value.json) })))
        }
        if let userInfo, !userInfo.isEmpty {
            payload.append(("user_info", .object(userInfo.sorted { $0.key < $1.key }.map { ($0.key, .string($0.value)) })))
        }
        var request = request("processor", method: "POST")
        request.httpBody = Data(JSON.object(payload).serialized.utf8)
        let (data, response) = try await send(request)
        let body = try checked(data, response)

        let result: TurnResult
        switch body["type"].string {
        case nil, "skip":
            result = .skipped
        case let type:
            result = .reply(terminal: type == "terminal", message: body["message"].string, choices: parseChoices(body["choices"]), media: parseMedia(body["media"]))
        }
        return TurnResponse(result: result, replacementVisitor: response.value(forHTTPHeaderField: Self.replacementVisitorHeader))
    }

    /// The visitor's latest conversation, or with `conversationID` one of their earlier ones.
    public func history(visitor: String, beforeID: Int? = nil, conversationID: Int? = nil) async throws -> History {
        var query = [URLQueryItem(name: "visitor", value: visitor)]
        if let beforeID { query.append(URLQueryItem(name: "before_id", value: String(beforeID))) }
        if let conversationID { query.append(URLQueryItem(name: "conversation_id", value: String(conversationID))) }
        let (data, response) = try await send(request("history", query: query))
        let body = try checked(data, response)
        let raw = body["messages"].array
        let last = raw.last
        return History(
            conversationId: body["conversation_id"].int,
            status: body["status"].string,
            messages: raw.compactMap(parseHistoryMessage),
            lastChoices: last.map { $0["role"].string != "user" ? parseChoices($0["choices"]) : [] } ?? [],
            hasOlder: body["has_older"].bool,
            cableToken: body["cable_token"].string,
            csatOffered: body["csat_offered"].bool,
            signedIn: body["signed_in"].bool,
            pastConversations: body["past_conversations"].bool
        )
    }

    /// A signed-in visitor's conversations, newest first.
    public func conversations(visitor: String, beforeID: Int? = nil) async throws -> ConversationPage {
        var query = [URLQueryItem(name: "visitor", value: visitor)]
        if let beforeID { query.append(URLQueryItem(name: "before_id", value: String(beforeID))) }
        let (data, response) = try await send(request("conversations", query: query))
        let body = try checked(data, response)
        return ConversationPage(conversations: body["conversations"].array.compactMap(parseConversationSummary), hasMore: body["has_more"].bool)
    }

    /// The thank-you to show.
    public func rate(visitor: String, conversationID: Int?, score: Int) async throws -> String? {
        var payload: [(String, JSON)] = [("visitor", .string(visitor)), ("score", .number(Double(score)))]
        if let conversationID { payload.append(("conversation_id", .number(Double(conversationID)))) }
        return try await post("csat", .object(payload))["thank_you"].string
    }

    /// Stores a file the visitor is about to send, bound to them.
    public func uploadMedia(visitor: String, upload: Upload) async throws -> MediaRef {
        let boundary = "UniversalChat-\(UUID().uuidString)"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
        }
        field("visitor", visitor)
        let safeName = upload.filename.replacingOccurrences(of: "\"", with: "")
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(safeName)\"\r\nContent-Type: \(upload.mimeType)\r\n\r\n".utf8))
        body.append(upload.data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var request = request("media", method: "POST")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await send(request)
        let json = try checked(data, response)
        guard let ref = json["ref"].string else { throw APIError(status: 200, code: nil, message: "No reference in response") }
        return MediaRef(ref: ref, mimeType: json["mime_type"].string ?? upload.mimeType, mediaType: json["media_type"].string ?? upload.kind)
    }

    /// The visitor has the conversation in view: our replies up to now are read.
    public func markRead(visitor: String, conversationID: Int?) async throws {
        var payload: [(String, JSON)] = [("visitor", .string(visitor))]
        if let conversationID { payload.append(("conversation_id", .number(Double(conversationID)))) }
        _ = try await post("read", .object(payload))
    }

    public func registerDevice(visitor: String, token: String) async throws {
        _ = try await post("devices", .object([("visitor", .string(visitor)), ("token", .string(token)), ("os", .string("ios"))]))
    }

    public func unregisterDevice(visitor: String, token: String) async throws {
        var request = request("devices", method: "DELETE")
        request.httpBody = Data(JSON.object([("visitor", .string(visitor)), ("token", .string(token))]).serialized.utf8)
        let (data, response) = try await send(request)
        _ = try checked(data, response)
    }

    private func post(_ path: String, _ payload: JSON) async throws -> JSON {
        var request = request(path, method: "POST")
        request.httpBody = Data(payload.serialized.utf8)
        let (data, response) = try await send(request)
        return try checked(data, response)
    }

    private func request(_ path: String, method: String = "GET", query: [URLQueryItem] = []) -> URLRequest {
        var components = URLComponents(url: serverURL.appendingPathComponent("deployments/\(deploymentID)/\(path)"), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if method != "GET" { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return request
    }

    private func checked(_ data: Data, _ response: HTTPURLResponse) throws -> JSON {
        let body = JSON.parse(data) ?? .object([])
        guard (200..<300).contains(response.statusCode) else {
            let error = body["error"].string
            throw APIError(status: response.statusCode, code: error, message: error ?? "Request failed (\(response.statusCode))")
        }
        return body
    }

    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            session.dataTask(with: request) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let response = response as? HTTPURLResponse {
                    continuation.resume(returning: (data ?? Data(), response))
                } else {
                    continuation.resume(throwing: URLError(.badServerResponse))
                }
            }.resume()
        }
    }
}
