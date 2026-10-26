import XCTest
@testable import UniversalChatCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Answers requests from a table of routes and records what was asked.
final class StubServer: URLProtocol {
    struct Reply {
        var status = 200
        var body = "{}"
        var headers: [String: String] = [:]
    }

    nonisolated(unsafe) static var routes: [String: () -> Reply] = [:]
    nonisolated(unsafe) static var requests: [(method: String, path: String, body: JSON?)] = []
    nonisolated(unsafe) static var rawBodies: [String: Data] = [:]
    static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let method = request.httpMethod ?? "GET"
        let path = request.url!.path
        let raw = Self.bodyData(of: request)
        let body = raw.flatMap(JSON.parse)
        Self.lock.lock()
        Self.requests.append((method, path, body))
        if let raw { Self.rawBodies[path] = raw }
        let reply = Self.routes["\(method) \(path)"]?() ?? Reply(status: 404)
        Self.lock.unlock()

        var headers = reply.headers
        headers["Content-Type"] = "application/json"
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func bodyData(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

@MainActor
final class ChatControllerTests: XCTestCase {
    var identity: InMemoryIdentityStore!

    override func setUp() async throws {
        identity = InMemoryIdentityStore()
        StubServer.routes = [:]
        StubServer.requests = []
        StubServer.rawBodies = [:]
        route("GET", "config") { .init(body: #"{"chatWidget":{"title":"Acme"},"entrypoints":[],"csat":{"enabled":true,"question":"Rate us"}}"#) }
        route("POST", "visitor") { .init(body: #"{"visitor":"anon-token"}"#) }
        route("GET", "history") { .init(body: #"{"messages":[],"has_older":false}"#) }
    }

    func route(_ method: String, _ path: String, _ reply: @escaping () -> StubServer.Reply) {
        StubServer.routes["\(method) /deployments/7/\(path)"] = reply
    }

    func makeAPI() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubServer.self]
        return APIClient(serverURL: URL(string: "https://acme.example.com")!, deploymentID: "7", session: URLSession(configuration: configuration))
    }

    func makeController(identity: IdentityStore? = nil) -> ChatController {
        ChatController(api: makeAPI(), identity: identity ?? self.identity)
    }

    func bodies(_ path: String) -> [JSON] {
        StubServer.lock.lock()
        defer { StubServer.lock.unlock() }
        return StubServer.requests.filter { $0.path == "/deployments/7/\(path)" }.compactMap(\.body)
    }

    func waitFor(_ controller: ChatController, _ predicate: @escaping (ChatState) -> Bool, file: StaticString = #filePath, line: UInt = #line) async -> ChatState {
        for _ in 0..<500 {
            if predicate(controller.state) { return controller.state }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("State never matched: \(controller.state)", file: file, line: line)
        return controller.state
    }

    func testASignedInVisitorOpensAnEarlierConversationFromTheirListReadOnly() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hello again"}],"has_older":false}"#)
        }
        route("GET", "conversations") {
            .init(body: #"{"conversations":[{"id":9,"ended":false,"last_message":{"role":"user","text":"hello again"}},{"id":4,"ended":true,"started_at":"2026-09-01T10:00:00Z","last_message":{"role":"bot","text":"Refund sent"}}],"has_more":false}"#)
        }

        let chat = makeController()
        chat.open()
        let opened = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        XCTAssertTrue(opened.pastConversations)

        chat.showPastConversations()
        let list = await waitFor(chat) { $0.past?.loading == false }.past!
        XCTAssertEqual(list.conversations.map(\.id), [9, 4])
        XCTAssertEqual(list.conversations.last?.lastText, "Refund sent")

        route("GET", "history") {
            .init(body: #"{"conversation_id":4,"status":"completed","messages":[{"id":20,"role":"user","text":"refund please"}],"has_older":false}"#)
        }
        chat.openPastConversation(list.conversations[1])
        let open = await waitFor(chat) { $0.past?.open != nil && $0.past?.transcriptLoading == false }
        XCTAssertEqual(open.past?.transcript.map(\.text), ["refund please"])
        XCTAssertEqual(open.messages.map(\.text), ["hello again"], "the chat underneath is untouched")

        chat.hidePastConversations()
        XCTAssertNil(chat.state.past)
    }

    func testPickingTheConversationStillRunningJustClosesTheList() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hi"}],"has_older":false}"#)
        }
        route("GET", "conversations") { .init(body: #"{"conversations":[{"id":9,"ended":false,"last_message":{"role":"user","text":"hi"}}],"has_more":false}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        chat.showPastConversations()
        let current = await waitFor(chat) { $0.past?.loading == false }.past!.conversations[0]

        chat.openPastConversation(current)

        XCTAssertNil(chat.state.past)
    }

    func testASignedInVisitorsConversationThatEndsBecomesOneToLookBackOn() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") { .init(body: #"{"messages":[],"has_older":false,"signed_in":true,"past_conversations":false}"#) }
        route("POST", "processor") { .init(body: #"{"type":"terminal","message":"Bye"}"#) }

        let chat = makeController()
        chat.open()

        let state = await waitFor(chat) { $0.ended }
        XCTAssertTrue(state.pastConversations)
    }

    func testAnAnonymousVisitorIsNotOfferedPastConversations() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":false,"past_conversations":false,"messages":[{"id":1,"role":"user","text":"hi"}],"has_older":false}"#)
        }

        let chat = makeController()
        chat.open()

        let state = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        XCTAssertFalse(state.pastConversations)
    }

    func testANotificationAboutAConversationThatHasEndedOpensItFromPastConversations() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hello again"}],"has_older":false}"#)
        }
        route("GET", "conversations") { .init(body: #"{"conversations":[],"has_more":false}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        route("GET", "history") {
            .init(body: #"{"conversation_id":4,"status":"completed","messages":[{"id":20,"role":"agent","text":"Refunded","author_name":"Sam","sent_at":"2026-09-02T09:00:00Z"}],"has_older":false}"#)
        }
        chat.openConversation(4)

        let state = await waitFor(chat) { $0.past?.open?.startedAt != nil }
        XCTAssertEqual(state.past?.open?.id, 4)
        XCTAssertEqual(state.past?.transcript.map(\.text), ["Refunded"])
        XCTAssertEqual(state.messages.map(\.text), ["hello again"])
    }

    func testANotificationAboutTheConversationOnScreenJustOpensTheChat() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hi"}],"has_older":false}"#)
        }

        let chat = makeController()
        chat.openConversation(9)
        chat.open()
        _ = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        try? await Task.sleep(nanoseconds: 100_000_000)

        XCTAssertNil(chat.state.past)
    }

    func testAnAnonymousVisitorsNotificationOpensTheChat() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","signed_in":false,"messages":[{"id":1,"role":"user","text":"hi"}],"has_older":false}"#)
        }

        let chat = makeController()
        chat.openConversation(4)
        chat.open()
        _ = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }
        try? await Task.sleep(nanoseconds: 100_000_000)

        XCTAssertNil(chat.state.past)
    }

    func testANewVisitorGetsAnAnonymousIdentityAndTheFlowStarts() async {
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Hi! Pick one","choices":{"b":"Orders","a":"Billing"}}"#) }

        let chat = makeController()
        chat.open()
        let state = await waitFor(chat) { !$0.messages.isEmpty }

        XCTAssertEqual(identity.anonymousVisitor, "anon-token")
        XCTAssertEqual(state.messages.map(\.text), ["Hi! Pick one"])
        XCTAssertEqual(state.choices.map(\.label), ["Orders", "Billing"], "choices keep the order the flow gave them")
        XCTAssertEqual(state.config.style.title, "Acme")
        XCTAssertEqual(bodies("processor").first?["input"], .string(""))
        XCTAssertEqual(bodies("processor").first?["visitor"], .string("anon-token"))
    }

    func testAReplyShowingOnTheOpenScreenIsReportedReadAndNothingOnceItCloses() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":2,"role":"agent","text":"Your card is on its way","sent_at":"2026-10-11T10:00:01Z"}],"has_older":false}"#)
        }
        route("POST", "read") { .init(status: 204, body: "") }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.messages.count == 1 }
        for _ in 0..<500 where bodies("read").isEmpty { try? await Task.sleep(nanoseconds: 10_000_000) }

        let report = bodies("read").first
        XCTAssertEqual(report?["visitor"].string, "held")
        XCTAssertEqual(report?["conversation_id"].int, 3)

        chat.close()
        let reported = bodies("read").count
        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(bodies("read").count, reported)
    }

    func testAnOpenConversationIsResumedWithItsLastChoices() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"user","text":"hello"},{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"}}],"has_older":true,"cable_token":"t"}"#)
        }

        let chat = makeController()
        chat.open()
        let state = await waitFor(chat) { !$0.loading && $0.messages.count == 2 }

        XCTAssertEqual(state.messages.map(\.author), [.user, .bot])
        XCTAssertEqual(state.choices.map(\.label), ["Yes"])
        XCTAssertTrue(state.hasOlder)
        XCTAssertTrue(bodies("processor").isEmpty)
    }

    func testATerminalReplyEndsTheConversationAndAsksForARating() async {
        var turns = 0
        route("POST", "processor") {
            turns += 1
            return turns == 1 ? .init(body: #"{"type":"prompt","message":"Pick","choices":{"a":"Orders"}}"#) : .init(body: #"{"type":"terminal","message":"Bye"}"#)
        }
        let chat = makeController()
        chat.open()
        let first = await waitFor(chat) { !$0.choices.isEmpty }

        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"completed","messages":[{"id":1,"role":"bot","text":"Bye"}],"csat_offered":true}"#) }
        chat.choose(first.choices[0])
        let state = await waitFor(chat) { $0.csat != nil }

        XCTAssertEqual(bodies("processor").last?["input"], .string("Orders"))
        XCTAssertTrue(state.ended)
        XCTAssertEqual(state.csat, .asking(question: "Rate us"))
    }

    func testAFailedSendIsMarkedFailedWithTheServersWordsAndCanBeRetried() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Ask away"}],"cable_token":"t"}"#) }
        route("POST", "processor") { .init(status: 429, body: #"{"error":"You're sending messages too fast."}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.messages.count == 1 }
        chat.send("hello")
        let failed = await waitFor(chat) { $0.messages.last?.delivery == .failed }
        XCTAssertEqual(failed.messages.last?.error, "You're sending messages too fast.")

        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Got it"}"#) }
        chat.retry(failed.messages.last!)
        let state = await waitFor(chat) { $0.messages.last?.text == "Got it" }
        XCTAssertEqual(state.messages.map(\.text), ["Ask away", "hello", "Got it"])
        XCTAssertEqual(state.messages[1].delivery, .sent)
    }

    func testAFlowWithWaysInOffersThemInsteadOfStarting() async {
        route("GET", "config") { .init(body: #"{"chatWidget":{},"entrypoints":[{"id":"billing","label":"Billing question"}]}"#) }
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"About billing"}"#) }

        let chat = makeController()
        chat.open()
        let offered = await waitFor(chat) { !$0.entrypoints.isEmpty }
        XCTAssertTrue(bodies("processor").isEmpty)

        chat.start(offered.entrypoints[0])
        let state = await waitFor(chat) { $0.messages.count == 2 }
        XCTAssertTrue(state.entrypoints.isEmpty)
        XCTAssertEqual(bodies("processor").first?["input"], .string("Billing question"))
    }

    func testDetailsAskedBeforeTheChatGoWithTheFirstTurnButNeverForASignedInUser() async {
        route("GET", "config") { .init(body: #"{"chatWidget":{"collectUserInfo":"before_chat"}}"#) }
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Hello Ada"}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.askForUserInfo }
        chat.submitUserInfo(["name": "Ada", "phone": " "])
        _ = await waitFor(chat) { !$0.messages.isEmpty }
        XCTAssertEqual(bodies("processor").first?["user_info"], .object([("name", .string("Ada"))]))

        let signedIn = InMemoryIdentityStore()
        signedIn.signedInVisitor = "user-token"
        let userChat = makeController(identity: signedIn)
        userChat.open()
        let state = await waitFor(userChat) { !$0.messages.isEmpty }
        XCTAssertFalse(state.askForUserInfo)
        XCTAssertEqual(bodies("processor").last?["visitor"], .string("user-token"))
    }

    func testAttributesTheAppSetGoWithEveryTurn() async {
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Hi"}"#) }

        let chat = makeController()
        chat.attributes = ["plan": "pro", "seats": 3, "beta": true, "gone": nil]
        chat.open()
        _ = await waitFor(chat) { !$0.messages.isEmpty }

        XCTAssertEqual(bodies("processor").first?["attributes"], .object([("beta", .bool(true)), ("gone", .null), ("plan", .string("pro")), ("seats", .number(3))]))
    }

    func upload(name: String = "receipt.png", mime: String = "image/png", size: Int = 3) -> Upload {
        Upload(filename: name, mimeType: mime, data: Data(repeating: 1, count: size), previewURL: URL(fileURLWithPath: "/tmp/receipt.png"))
    }

    func testAFileIsUploadedFirstAndTheTurnClaimsIt() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Send a photo"}],"cable_token":"t"}"#) }
        route("POST", "media") { .init(body: #"{"ref":"signed-ref","mime_type":"image/png","media_type":"image"}"#) }
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Got it"}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.messages.count == 1 }
        chat.send("Here", upload: upload())
        let state = await waitFor(chat) { $0.messages.last?.text == "Got it" }

        let form = String(decoding: StubServer.rawBodies["/deployments/7/media"] ?? Data(), as: UTF8.self)
        XCTAssertTrue(form.contains("name=\"visitor\"\r\n\r\nheld"))
        XCTAssertTrue(form.contains("filename=\"receipt.png\""))
        XCTAssertEqual(bodies("processor").last?["media_url"], .string("signed-ref"))
        XCTAssertEqual(bodies("processor").last?["media_type"], .string("image"))
        XCTAssertEqual(state.messages[1].attachments.map(\.filename), ["receipt.png"])
        XCTAssertEqual(state.messages[1].delivery, .sent)
        XCTAssertNil(state.messages[1].pendingUpload)
    }

    func testAFileTheServerWouldRefuseIsNeverUploaded() async {
        let chat = makeController()
        chat.send("", upload: upload(size: Uploads.maxBytes + 1))
        XCTAssertEqual(chat.state.error, "That file is too large. The limit is 25 MB.")
        chat.send("", upload: upload(name: "a.zip", mime: "application/zip"))
        XCTAssertEqual(chat.state.error, "That type of file can't be sent here.")
        XCTAssertTrue(bodies("media").isEmpty && StubServer.rawBodies.isEmpty)
    }

    func testARefusedTokenGetsAFreshVisitorAndAFailedUploadRetriesWithItsFile() async {
        identity.anonymousVisitor = "lapsed"
        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Send it"}],"cable_token":"t"}"#) }
        var uploads = 0
        route("POST", "media") {
            uploads += 1
            switch uploads {
            case 1: return .init(status: 401, body: #"{"error":"visitor is required"}"#)
            case 2: return .init(status: 422, body: #"{"error":"File is not an accepted type"}"#)
            default: return .init(body: #"{"ref":"r","mime_type":"application/pdf","media_type":"document"}"#)
            }
        }
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Thanks"}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.messages.count == 1 }
        chat.send("", upload: upload(name: "doc.pdf", mime: "application/pdf"))
        let failed = await waitFor(chat) { $0.messages.last?.delivery == .failed }
        XCTAssertEqual(identity.anonymousVisitor, "anon-token")
        XCTAssertEqual(failed.messages.last?.error, "File is not an accepted type")

        chat.retry(failed.messages.last!)
        let state = await waitFor(chat) { $0.messages.last?.text == "Thanks" }
        XCTAssertEqual(state.messages[1].attachments.map(\.filename), ["doc.pdf"])
        XCTAssertEqual(bodies("processor").last?["media_url"], .string("r"))
    }

    func testHistoryShowsEveryFileAMessageCarried() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"agent","text":"","attachments":[{"url":"https://acme/files/x","filename":"receipt.png","kind":"image","content_type":"image/png"},{"url":"https://acme/files/y","filename":"terms.pdf","content_type":"application/pdf"},{"url":null,"filename":"lost.pdf","kind":"document"}]}],"cable_token":"t"}"#)
        }

        let chat = makeController()
        chat.open()
        let state = await waitFor(chat) { $0.messages.count == 1 }

        XCTAssertEqual(state.messages.first?.attachments, [
            Attachment(url: URL(string: "https://acme/files/x")!, kind: "image", filename: "receipt.png", mimeType: "image/png"),
            Attachment(url: URL(string: "https://acme/files/y")!, kind: "document", filename: "terms.pdf", mimeType: "application/pdf"),
        ])
    }

    func testARefusedSignedInVisitorFallsBackToTheReplacement() async {
        identity.signedInVisitor = "lapsed"
        identity.signedInUserID = "42"
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Hi"}"#, headers: ["X-Webchat-Visitor": "fresh-anon"]) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.messages.isEmpty }

        XCTAssertNil(identity.signedInVisitor)
        XCTAssertEqual(identity.anonymousVisitor, "fresh-anon")
    }

    func testADeploymentThatIsNotAnsweringShowsAsUnavailable() async {
        route("GET", "config") { .init(status: 404) }

        let chat = makeController()
        chat.open()

        let state = await waitFor(chat) { !$0.loading }
        XCTAssertTrue(state.unavailable)
    }

    func testSigningInExchangesTheUserToken() async throws {
        route("POST", "visitor") { .init(body: #"{"visitor":"user-visitor","user_id":"42"}"#) }

        let (visitor, userID) = try await makeAPI().signedInVisitor(userToken: "jwt")

        XCTAssertEqual(visitor, "user-visitor")
        XCTAssertEqual(userID, "42")
        XCTAssertEqual(bodies("visitor").last?["user_token"], .string("jwt"))
    }

    func testARefusedUserTokenThrows() async {
        route("POST", "visitor") { .init(status: 401, body: #"{"error":"invalid_user_token"}"#) }

        do {
            _ = try await makeAPI().signedInVisitor(userToken: "bad")
            XCTFail("expected a refusal")
        } catch let error as APIError {
            XCTAssertEqual(error.status, 401)
            XCTAssertEqual(error.code, "invalid_user_token")
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testCableEventsParse() async {
        let agent = JSON.parse(Data(#"{"event":"agent_message","data":{"platform_message_id":"m1","body":"On it","author_name":"Sam"}}"#.utf8))!
        XCTAssertEqual(CableClient.parseEvent(agent), .agentMessage(ChatMessage(id: "m1", author: .agent, text: "On it", authorName: "Sam", platformId: "m1")))
        let closed = JSON.parse(Data(#"{"event":"conversation_closed","data":{"conversation_id":3,"csat_offered":true}}"#.utf8))!
        XCTAssertEqual(CableClient.parseEvent(closed), .conversationClosed(conversationId: 3, csatOffered: true))
        XCTAssertEqual(CableClient.backoff(0), 1)
        XCTAssertEqual(CableClient.backoff(10), 30)
    }

    func testAnAgentReplyOnTheCableCarriesEveryFileItWasSentWith() async {
        let agent = JSON.parse(Data(#"{"event":"agent_message","data":{"platform_message_id":"m2","body":null,"author_name":"Sam","attachments":[{"url":"https://acme/files/a","filename":"a.png","kind":"image","content_type":"image/png"},{"url":"https://acme/files/b","filename":"b.pdf","kind":"document","content_type":"application/pdf"}]}}"#.utf8))!
        guard case .agentMessage(let message)? = CableClient.parseEvent(agent) else { return XCTFail("not an agent message") }
        XCTAssertEqual(message.text, "")
        XCTAssertEqual(message.attachments, [
            Attachment(url: URL(string: "https://acme/files/a")!, kind: "image", filename: "a.png", mimeType: "image/png"),
            Attachment(url: URL(string: "https://acme/files/b")!, kind: "document", filename: "b.pdf", mimeType: "application/pdf"),
        ])
    }

    func testAnAgentReplyWithNeitherTextNorAFileIsNotShown() async {
        let empty = JSON.parse(Data(#"{"event":"agent_message","data":{"platform_message_id":"m3","body":"","attachments":[]}}"#.utf8))!
        XCTAssertNil(CableClient.parseEvent(empty))
    }

    func testReopeningTheScreenShowsRepliesThatArrivedWhileItWasClosedOnceEach() async {
        identity.anonymousVisitor = "held"
        let first = #"{"id":1,"role":"user","text":"hello"},{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"}}"#
        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"active","messages":["# + first + #"],"has_older":false,"cable_token":"t"}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { $0.messages.count == 2 }
        chat.close()

        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"active","messages":["# + first + #",{"id":3,"role":"agent","text":"Here you go","author_name":"Sam","attachments":[{"url":"https://acme/files/a","filename":"a.pdf","kind":"document"},{"url":"https://acme/files/b","filename":"b.pdf","kind":"document"}]}],"has_older":false,"cable_token":"t"}"#)
        }
        chat.open()
        let state = await waitFor(chat) { $0.messages.count == 3 }

        XCTAssertEqual(state.messages.map(\.text), ["hello", "Pick", "Here you go"])
        XCTAssertEqual(state.messages.last?.attachments.map(\.filename), ["a.pdf", "b.pdf"])
        XCTAssertEqual(state.choices.map(\.label), ["Yes"])
        XCTAssertFalse(state.ended)
    }

    func testReopeningAfterTheConversationEndedMeanwhileClosesItAndAsksForARating() async {
        identity.anonymousVisitor = "held"
        let first = #"{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"}}"#
        route("GET", "history") { .init(body: #"{"conversation_id":3,"status":"active","messages":["# + first + #"],"has_older":false,"cable_token":"t"}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.choices.isEmpty }
        chat.close()

        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"completed","messages":["# + first + #",{"id":3,"role":"agent","text":"All sorted"}],"has_older":false,"csat_offered":true}"#)
        }
        chat.open()
        let state = await waitFor(chat) { $0.ended }

        XCTAssertEqual(state.messages.map(\.text), ["Pick", "All sorted"])
        XCTAssertTrue(state.choices.isEmpty)
        XCTAssertEqual(state.csat, .asking(question: "Rate us"))
    }

    func testReopeningReplacesWhatThisSessionShowedWithTheServersCopyInsteadOfRepeatingIt() async {
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"Hi! Pick one","choices":{"a":"Orders"}}"#) }

        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.messages.isEmpty }
        chat.close()

        route("GET", "history") {
            .init(body: #"{"conversation_id":5,"status":"active","messages":[{"id":1,"role":"bot","text":"Hi! Pick one","choices":{"a":"Orders"}},{"id":2,"role":"agent","text":"Sam here"}],"has_older":false,"cable_token":"t"}"#)
        }
        chat.open()
        let state = await waitFor(chat) { $0.messages.contains { $0.text == "Sam here" } }

        XCTAssertEqual(state.messages.map(\.text), ["Hi! Pick one", "Sam here"])
    }

    func testANotificationAboutTheConversationOnScreenPicksUpTheReplyItWasAbout() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") { .init(body: #"{"conversation_id":9,"status":"active","messages":[{"id":1,"role":"user","text":"hi"}],"has_older":false}"#) }
        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.loading && !$0.messages.isEmpty }

        route("GET", "history") {
            .init(body: #"{"conversation_id":9,"status":"active","messages":[{"id":1,"role":"user","text":"hi"},{"id":2,"role":"agent","text":"Hello!"}],"has_older":false}"#)
        }
        chat.openConversation(9)
        let state = await waitFor(chat) { $0.messages.count == 2 }

        XCTAssertEqual(state.messages.last?.text, "Hello!")
        XCTAssertNil(state.past)
    }

    func testMergingHistoryKeepsEarlierPagesInFrontAndUnsentMessagesAfter() {
        let older = ChatMessage(id: "history-1", author: .bot, text: "older", historyId: 1)
        let stale = ChatMessage(id: "history-5", author: .agent, text: "on page", historyId: 5)
        let live = ChatMessage(id: "m1", author: .agent, text: "live copy", platformId: "m1")
        let failed = ChatMessage(id: "local-1", author: .user, text: "retry me", delivery: .failed)
        let page = [ChatMessage(id: "history-5", author: .agent, text: "on page", historyId: 5),
                    ChatMessage(id: "history-6", author: .agent, text: "live copy", historyId: 6, platformId: "m1")]

        XCTAssertEqual(ChatController.mergeHistory(shown: [older, stale, live, failed], latest: page, latestHasOlder: true), [older] + page + [failed])
        XCTAssertEqual(ChatController.mergeHistory(shown: [older, stale, live, failed], latest: page, latestHasOlder: false), page + [failed])
    }

    func testMergingHistoryKeepsALiveReplyThePageWasReadTooEarlyToHaveOnceBeforeUnsentMessages() {
        let sent = ChatMessage(id: "local-1", author: .user, text: "hello")
        let earlier = ChatMessage(id: "m1", author: .agent, text: "first reply", platformId: "m1")
        let late = ChatMessage(id: "m2", author: .agent, text: "arrived after the page", platformId: "m2")
        let sending = ChatMessage(id: "local-2", author: .user, text: "still going", delivery: .sending)
        let page = [
            ChatMessage(id: "history-1", author: .user, text: "hello", historyId: 1),
            ChatMessage(id: "history-2", author: .agent, text: "first reply", historyId: 2, platformId: "m1"),
        ]

        XCTAssertEqual(ChatController.mergeHistory(shown: [sent, earlier, late, sending], latest: page, latestHasOlder: false), page + [late, sending])
    }

    func testMergingHistoryKeepsALiveReplyShownBeforeThePageInFrontOfIt() {
        let live = ChatMessage(id: "m9", author: .agent, text: "from before", platformId: "m9")
        let onPage = ChatMessage(id: "history-5", author: .agent, text: "on page", historyId: 5)

        XCTAssertEqual(ChatController.mergeHistory(shown: [live, onPage], latest: [onPage], latestHasOlder: true), [live, onPage])
    }

    func testHistoryReadsEachMessagesLiveID() {
        let message = parseHistoryMessage(JSON.parse(Data(#"{"id":4,"role":"agent","text":"Sam here","platform_message_id":"m4"}"#.utf8))!)

        XCTAssertEqual(message?.platformId, "m4")
        XCTAssertEqual(message?.historyId, 4)
    }

    func testReopeningOnADifferentConversationShowsItInPlaceOfTheOneOnScreen() async {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            .init(body: #"{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Old chat","choices":{"x":"Yes"}}],"has_older":true,"cable_token":"t"}"#)
        }
        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { !$0.messages.isEmpty }
        chat.close()

        route("GET", "history") {
            .init(body: #"{"conversation_id":4,"status":"active","messages":[{"id":7,"role":"bot","text":"New chat","choices":{"y":"Go"}}],"has_older":false,"cable_token":"t2"}"#)
        }
        chat.open()
        let state = await waitFor(chat) { $0.messages.contains { $0.text == "New chat" } }

        XCTAssertEqual(state.messages.map(\.text), ["New chat"])
        XCTAssertEqual(state.choices.map(\.label), ["Go"])
        XCTAssertFalse(state.hasOlder)
    }

    func testReopeningAfterAConversationWithNothingOnScreenEndedMeanwhileClosesItAndAsksForARating() async {
        route("POST", "processor") { .init(body: #"{"type":"prompt","message":"","choices":{}}"#) }
        let chat = makeController()
        chat.open()
        _ = await waitFor(chat) { [self] in !$0.loading && !$0.typing && !bodies("processor").isEmpty }
        chat.close()

        route("GET", "history") { .init(body: #"{"conversation_id":6,"status":"completed","messages":[],"has_older":false,"csat_offered":true}"#) }
        chat.open()
        let state = await waitFor(chat) { $0.ended }

        XCTAssertTrue(state.messages.isEmpty)
        XCTAssertEqual(state.csat, .asking(question: "Rate us"))
    }

    func testJSONKeepsKeyOrderAndRoundTripsEscapes() async {
        let json = JSON.parse(Data(#"{"z":1,"a":"line\nbreak \"q\" é 😀","n":null,"l":[true,false,2.5]}"#.utf8))!
        XCTAssertEqual(json.orderedEntries.map(\.0), ["z", "a", "n", "l"])
        XCTAssertEqual(json["a"].string, "line\nbreak \"q\" é 😀")
        XCTAssertEqual(JSON.parse(Data(json.serialized.utf8)), json)
        XCTAssertNil(JSON.parse(Data("{nope".utf8)))
    }
}

final class WidgetStyleTests: XCTestCase {
    func parse(_ json: String) -> WidgetStyle { .parse(JSON.parse(Data(json.utf8))!, fontStylesheet: nil) }

    func testNothingSavedReadsAsTheWebsiteWidgetsDefaults() {
        let style = parse("{}")
        XCTAssertEqual(style, .default)
    }

    func testSavedColoursOverridePerThemeAndKeyInAnyHexForm() {
        let style = parse(##"{"lightColors":{"userMessageBackgroundColor":"#4f46e5","headerColor":"#abc","borderColor":"#11223380"},"darkColors":{"botMessageTextColor":"#ffffff"}}"##)
        XCTAssertEqual(style.light.userBackground, 0xFF4F46E5)
        XCTAssertEqual(style.light.header, 0xFFAABBCC)
        XCTAssertEqual(style.light.border, 0x80112233)
        XCTAssertEqual(style.light.botText, Palette.light.botText)
        XCTAssertEqual(style.dark.botText, 0xFFFFFFFF)
    }

    func testThemeSizesAndFontReadAsTheWidgetReadsThem() {
        let style = parse(#"{"theme":"system","fontSize":"18px","borderRadius":"0.5rem","fontFamily":"'Poppins', sans-serif"}"#)
        XCTAssertEqual(style.theme, .system)
        XCTAssertEqual(style.fontSize, 18)
        XCTAssertEqual(style.borderRadius, 8)
        XCTAssertEqual(style.fontFamily, "Poppins")
        XCTAssertNil(WidgetStyle.firstFamily("ui-sans-serif, system-ui, sans-serif"))
    }

    func testAGoogleStylesheetYieldsOneUprightFilePerWeight() {
        let css = """
        @font-face { font-family: 'Poppins'; font-style: italic; font-weight: 400; src: url(https://x/i.ttf) format('truetype'); }
        @font-face { font-family: 'Poppins'; font-style: normal; font-weight: 400; src: url(https://x/400.ttf) format('truetype'); }
        @font-face { font-family: 'Poppins'; font-style: normal; font-weight: 600; src: url(https://x/600.ttf) format('truetype'); }
        """
        XCTAssertEqual(GoogleFontCss.files(css), [400: URL(string: "https://x/400.ttf")!, 600: URL(string: "https://x/600.ttf")!])
    }
}
