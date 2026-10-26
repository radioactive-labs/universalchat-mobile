import XCTest
@testable import UniversalChatCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Runs against a real server, only when told where one is:
///
///   UCB_E2E_SERVER       the server URL
///   UCB_E2E_DEPLOYMENT   a mobile deployment whose flow asks a question with
///                        choices, then hands off to a person
///   UCB_E2E_JWT          a user token signed with that deployment's secret
///   UCB_E2E_HANDOFF_FILE where to write the conversation id once handed off,
///                        so whoever drives the test can reply as an agent
@MainActor
final class EndToEndTests: XCTestCase {
    func testASignedInUserChatsThroughToAPersonAndGetsTheirReplyLive() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["UCB_E2E_SERVER"].flatMap(URL.init(string:)), let deployment = env["UCB_E2E_DEPLOYMENT"], let jwt = env["UCB_E2E_JWT"] else {
            throw XCTSkip("Set UCB_E2E_SERVER, UCB_E2E_DEPLOYMENT and UCB_E2E_JWT to run against a server")
        }
        let api = APIClient(serverURL: server, deploymentID: deployment)
        let identity = InMemoryIdentityStore()

        let (visitor, userID) = try await api.signedInVisitor(userToken: jwt)
        identity.signedInVisitor = visitor
        identity.signedInUserID = userID
        XCTAssertFalse(userID.isEmpty)

        let chat = ChatController(api: api, identity: identity) { onEvent, onRejected in
            CableClient(serverOrigin: api.serverOrigin, onEvent: onEvent, onRejected: onRejected)
        }
        chat.open()
        let asked = try await waitFor(chat) { !$0.choices.isEmpty }
        XCTAssertEqual(asked.choices.map(\.label), ["Orders", "Billing"])

        chat.choose(asked.choices[0])
        let handedOff = try await waitFor(chat) { $0.messages.contains { $0.text.contains("Connecting you") } && !$0.typing }
        XCTAssertFalse(handedOff.ended)

        // Give the cable a moment to subscribe before the agent replies.
        try await Task.sleep(nanoseconds: 3_000_000_000)
        let conversation = try await api.history(visitor: visitor)
        if let file = env["UCB_E2E_HANDOFF_FILE"], let id = conversation.conversationId {
            try String(id).write(toFile: file, atomically: true, encoding: .utf8)
        }

        let replied = try await waitFor(chat, seconds: 60) { $0.messages.contains { $0.author == .agent } }
        let reply = replied.messages.last { $0.author == .agent }!
        XCTAssertEqual(reply.text, "Hi, Sam here. Let me look at your order.")
        XCTAssertEqual(reply.authorName, "Sam")

        chat.send("Thanks Sam")
        _ = try await waitFor(chat) { $0.messages.last?.text == "Thanks Sam" && $0.messages.last?.delivery == .sent }
        chat.close()
    }

    private func waitFor(_ chat: ChatController, seconds: Double = 15, _ predicate: @escaping (ChatState) -> Bool) async throws -> ChatState {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if predicate(chat.state) { return chat.state }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("State never matched: \(chat.state)")
        return chat.state
    }
}
