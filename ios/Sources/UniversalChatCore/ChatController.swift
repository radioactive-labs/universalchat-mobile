import Foundation

/// What the chat screen shows.
public struct ChatState: Equatable, Sendable {
    public var config: ChatConfig = .empty
    public var loading = true
    /// The deployment is switched off, or not on the workspace's plan.
    public var unavailable = false
    public var messages: [ChatMessage] = []
    public var choices: [Choice] = []
    /// Ways into the flow, shown while no conversation is running.
    public var entrypoints: [Entrypoint] = []
    public var typing = false
    public var ended = false
    public var hasOlder = false
    public var loadingOlder = false
    public var askForUserInfo = false
    /// Whether the conversation on screen was started in this session, rather than resumed.
    public var startedHere = false
    public var csat: CsatPrompt?
    public var error: String?
    public var signedIn = false
    /// Offer the visitor their earlier conversations. Signed-in visitors only.
    public var pastConversations = false
    /// The list of earlier conversations, while it is open over the chat.
    public var past: PastConversations?

    public init() {}
}

/// A signed-in visitor looking back: their list, and the one they opened from it, read-only.
public struct PastConversations: Equatable, Sendable {
    public var conversations: [ConversationSummary] = []
    public var hasMore = false
    public var loading = true
    public var failed = false
    public var open: ConversationSummary?
    public var transcript: [ChatMessage] = []
    public var transcriptHasOlder = false
    public var transcriptLoading = false

    public init(conversations: [ConversationSummary] = [], hasMore: Bool = false, loading: Bool = true, open: ConversationSummary? = nil, transcript: [ChatMessage] = []) {
        self.conversations = conversations
        self.hasMore = hasMore
        self.loading = loading
        self.open = open
        self.transcript = transcript
    }
}

public enum CsatPrompt: Equatable, Sendable {
    case asking(question: String)
    case thanked(String)
}

/// One deployment's conversation for the current visitor: resumes it, runs
/// turns, follows the cable while open, and asks for a rating when one is due.
/// Mirrors the website widget's behaviour so a conversation reads the same on
/// either.
@MainActor
public final class ChatController {
    public static let connectError = "Couldn't reach the chat service. Check your connection and try again."
    static let readReportInterval: TimeInterval = 3

    public private(set) var state = ChatState() {
        didSet {
            guard state != oldValue else { return }
            observers.values.forEach { $0(state) }
            if Self.ours(state) != Self.ours(oldValue) { reportRead() }
        }
    }

    private let api: APIClient
    private let identity: IdentityStore
    private var cable: CableClient?
    private var observers: [UUID: (ChatState) -> Void] = [:]
    private var conversationID: Int?
    private var pendingUserInfo: [String: String]?
    private var pendingConversation: Int?

    /// Custom attributes the app set about the visitor, sent with every turn.
    public var attributes: [String: AttributeValue] = [:]
    private var opened = false
    // Whether the chat screen is showing, and the pending report of what the
    // visitor has read there. Reported at most every few seconds.
    private var inView = false
    private var readReport: Task<Void, Never>?
    private var lastReadReport = Date.distantPast
    private var turnRunning = false
    private var queued: [@MainActor () async -> Void] = []

    public init(api: APIClient, identity: IdentityStore, makeCable: ((@escaping @MainActor (CableEvent) -> Void, @escaping @MainActor () -> Void) -> CableClient?)? = nil) {
        self.api = api
        self.identity = identity
        cable = makeCable?({ [weak self] event in self?.onCableEvent(event) }, { [weak self] in
            Task { await self?.refreshCable() }
        })
    }

    /// Calls `observer` on every change. Returns a token for `removeObserver`.
    @discardableResult
    public func observe(_ observer: @escaping (ChatState) -> Void) -> UUID {
        let id = UUID()
        observers[id] = observer
        observer(state)
        return id
    }

    public func removeObserver(_ id: UUID) {
        observers[id] = nil
    }

    /// Called when the chat screen opens. Loads the conversation once per
    /// controller, and on every later opening catches up on what arrived
    /// while it was closed.
    public func open() {
        inView = true
        reportRead()
        if opened {
            catchUp()
            return
        }
        opened = true
        Task { await load() }
    }

    /// Called when the chat screen closes; live updates stop until it opens again.
    public func close() {
        inView = false
        readReport?.cancel()
        cable?.stop()
    }

    private static func ours(_ state: ChatState) -> Int {
        state.messages.filter { $0.author != .user }.count
    }

    /// Tells the server the visitor has read what we sent, while the screen is
    /// showing. Best effort: a failure only leaves our replies showing as
    /// sent in the inbox a little longer.
    private func reportRead() {
        guard inView, Self.ours(state) > 0 else { return }
        readReport?.cancel()
        let wait = max(0, lastReadReport.addingTimeInterval(Self.readReportInterval).timeIntervalSinceNow)
        readReport = Task { [weak self] in
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
            guard let self, !Task.isCancelled else { return }
            self.lastReadReport = Date()
            guard let visitor = await self.visitor() else { return }
            try? await self.api.markRead(visitor: visitor, conversationID: self.conversationID)
        }
    }

    /// Sends what the visitor typed, with the file they attached, if any.
    public func send(_ text: String, upload: Upload? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || upload != nil else { return }
        if let upload, let refusal = Uploads.refusal(upload) {
            state.error = refusal
            return
        }
        enqueueTurn(trimmed, opensConversation: false, upload: upload)
    }

    public func choose(_ choice: Choice) { send(choice.label) }

    public func start(_ entrypoint: Entrypoint) { enqueueTurn(entrypoint.label, opensConversation: true) }

    /// None of the ways in fit: start the flow from its beginning.
    public func startWithoutEntrypoint() { enqueueTurn("", opensConversation: true) }

    public func retry(_ message: ChatMessage) {
        state.messages.removeAll { $0.id == message.id }
        send(message.text, upload: message.pendingUpload)
    }

    /// A finished conversation starts over: at the ways in when the flow has them, otherwise straight away.
    public func restart() {
        conversationID = nil
        cable?.stop()
        state.messages = []
        state.choices = []
        state.ended = false
        state.csat = nil
        state.hasOlder = false
        state.startedHere = false
        begin()
    }

    public func submitUserInfo(_ info: [String: String]) {
        identity.userInfoCollected = true
        pendingUserInfo = info.filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
        let starting = state.messages.isEmpty && !state.ended
        state.askForUserInfo = false
        if starting { enqueueTurn("", opensConversation: true) }
    }

    public func dismissUserInfo() {
        identity.userInfoCollected = true
        let starting = state.messages.isEmpty
        state.askForUserInfo = false
        if starting { enqueueTurn("", opensConversation: true) }
    }

    public func rate(_ score: Int) {
        Task {
            guard let visitor = await visitor() else { return }
            do {
                let thanks = try await api.rate(visitor: visitor, conversationID: conversationID, score: score)
                state.csat = .thanked(thanks ?? state.config.csat.thankYou ?? "Thanks for your feedback.")
            } catch {
                state.csat = nil
            }
        }
    }

    public func dismissError() { state.error = nil }

    /// Shows something that went wrong on the visitor's side, such as a file that could not be read.
    public func showError(_ message: String) { state.error = message }

    public func loadOlder() {
        guard let oldest = state.messages.first(where: { $0.historyId != nil })?.historyId, state.hasOlder, !state.loadingOlder else { return }
        state.loadingOlder = true
        Task {
            defer { state.loadingOlder = false }
            guard let visitor = await visitor(), let page = try? await api.history(visitor: visitor, beforeID: oldest) else { return }
            state.messages = page.messages + state.messages
            state.hasOlder = page.hasOlder
        }
    }

    /// Opens the signed-in visitor's list of earlier conversations over the chat.
    public func showPastConversations() {
        state.past = PastConversations()
        Task { await fetchPastConversations(beforeID: nil) }
    }

    public func loadMorePastConversations() {
        guard let past = state.past, !past.loading, past.hasMore else { return }
        state.past?.loading = true
        Task { await fetchPastConversations(beforeID: past.conversations.last?.id) }
    }

    /// The conversation still running is the chat underneath, so picking it just closes the list.
    public func openPastConversation(_ conversation: ConversationSummary) {
        if !conversation.ended && conversation.id == conversationID { return hidePastConversations() }
        state.past?.open = conversation
        state.past?.transcript = []
        state.past?.transcriptHasOlder = false
        state.past?.transcriptLoading = true
        Task { await fetchTranscript(conversation, beforeID: nil) }
    }

    public func loadOlderInPastConversation() {
        guard let past = state.past, let open = past.open, past.transcriptHasOlder, !past.transcriptLoading,
              let oldest = past.transcript.first(where: { $0.historyId != nil })?.historyId else { return }
        state.past?.transcriptLoading = true
        Task { await fetchTranscript(open, beforeID: oldest) }
    }

    /// Back from a transcript to the list.
    public func closePastConversation() {
        state.past?.open = nil
        state.past?.transcript = []
    }

    public func hidePastConversations() { state.past = nil }

    private func fetchPastConversations(beforeID: Int?) async {
        guard let visitor = await visitor(), let page = try? await api.conversations(visitor: visitor, beforeID: beforeID) else {
            state.past?.loading = false
            state.past?.failed = true
            return
        }
        guard state.past != nil else { return }
        state.past?.conversations = beforeID == nil ? page.conversations : (state.past?.conversations ?? []) + page.conversations
        state.past?.hasMore = page.hasMore
        state.past?.loading = false
        state.past?.failed = false
    }

    private func fetchTranscript(_ conversation: ConversationSummary, beforeID: Int?) async {
        let page: History?
        if let visitor = await visitor() {
            page = try? await api.history(visitor: visitor, beforeID: beforeID, conversationID: conversation.id)
        } else {
            page = nil
        }
        guard state.past?.open?.id == conversation.id else { return }
        state.past?.transcriptLoading = false
        guard let page else { return }
        state.past?.transcript = page.messages + (state.past?.transcript ?? [])
        state.past?.transcriptHasOlder = page.hasOlder
    }

    /// The visitor changed (signed in or out): drop what is on screen and load theirs.
    public func reset() {
        cable?.stop()
        conversationID = nil
        pendingUserInfo = nil
        opened = false
        pendingConversation = nil
        state = ChatState()
    }

    /// Opens on `conversationID`, the conversation a tapped notification was
    /// about. While it is still running it is the chat itself; once it has
    /// ended, a signed-in visitor sees it from their past conversations. An
    /// anonymous visitor has no past conversations, so they get the chat.
    public func openConversation(_ conversationID: Int) {
        if opened && !state.loading {
            Task { await showConversation(conversationID, justLoaded: false) }
        } else {
            pendingConversation = conversationID
        }
    }

    /// The notification tapped while the chat was loading, once it has loaded.
    private func showPendingConversation() async {
        guard let id = pendingConversation else { return }
        pendingConversation = nil
        await showConversation(id, justLoaded: true)
    }

    /// `justLoaded` when the conversation on screen was fetched a moment ago, so there is nothing to catch up on.
    private func showConversation(_ id: Int, justLoaded: Bool) async {
        // The notification may be about a reply that arrived while the chat was closed.
        if id == conversationID {
            if !justLoaded { catchUp() }
            return
        }
        guard state.signedIn else { return }
        // Only one conversation runs at a time, and it is the one on screen, so this one has ended.
        let placeholder = ConversationSummary(id: id, ended: true, startedAt: nil, lastAuthor: .bot, lastAuthorName: nil, lastText: "", lastHasAttachment: false, lastSentAt: nil)
        var past = PastConversations(open: placeholder)
        past.transcriptLoading = true
        state.past = past
        Task { await fetchPastConversations(beforeID: nil) }
        await fetchTranscript(placeholder, beforeID: nil)
        // Its title names the day it started, which only the transcript knows.
        if state.past?.open?.id == id { state.past?.open?.startedAt = state.past?.transcript.first?.sentAt }
    }

    private func load() async {
        await loadConversation()
        await showPendingConversation()
    }

    private func loadConversation() async {
        let config: ChatConfig?
        do {
            config = try await api.config()
        } catch {
            state.loading = false
            state.error = Self.connectError
            opened = false
            return
        }
        guard let config else {
            state.loading = false
            state.unavailable = true
            return
        }
        state.config = config

        let visitor = await visitor()
        var history: History?
        if let visitor { history = try? await api.history(visitor: visitor) }
        if let history {
            state.signedIn = history.signedIn
            state.pastConversations = history.pastConversations
        }

        if let history, !history.messages.isEmpty {
            conversationID = history.conversationId
            if let token = history.cableToken { cable?.subscribe(token) }
            show(history)
            return
        }

        state.loading = false
        begin()
    }

    /// Shows `history` as the conversation on screen, in place of whatever was
    /// there. One that has ended offers the ways in when the flow has them,
    /// and otherwise stays readable with its rating.
    private func show(_ history: History) {
        var next = state
        next.loading = false
        next.typing = false
        next.startedHere = false
        next.entrypoints = []
        next.choices = []
        next.csat = nil
        next.messages = history.messages
        next.hasOlder = history.hasOlder
        next.ended = false
        if history.status == "completed" && !state.config.entrypoints.isEmpty {
            next.messages = []
            next.hasOlder = false
            next.entrypoints = state.config.entrypoints
        } else if history.status == "completed" {
            next.ended = true
            next.csat = csatPrompt(offered: history.csatOffered)
        } else {
            next.choices = history.lastChoices
        }
        state = next
    }

    /// No conversation yet: offer the ways in, ask for details first, or start the flow.
    private func begin() {
        if !state.config.entrypoints.isEmpty {
            state.entrypoints = state.config.entrypoints
        } else if needsUserInfo("before_chat") {
            state.askForUserInfo = true
        } else {
            enqueueTurn("", opensConversation: true)
        }
    }

    // A signed-in user's details come from the customer's server, so they are never asked for.
    private func needsUserInfo(_ moment: String) -> Bool {
        state.config.collectUserInfo == moment && !identity.userInfoCollected && identity.signedInVisitor == nil
    }

    private func enqueueTurn(_ input: String, opensConversation: Bool, upload: Upload? = nil) {
        enqueue { [weak self] in await self?.runTurn(input, opensConversation: opensConversation, upload: upload) }
    }

    /// Turns, and catching up with the server, run one at a time in the order they were asked for.
    private func enqueue(_ work: @escaping @MainActor () async -> Void) {
        queued.append(work)
        guard !turnRunning else { return }
        turnRunning = true
        Task {
            while !queued.isEmpty {
                await queued.removeFirst()()
            }
            turnRunning = false
        }
    }

    private func runTurn(_ input: String, opensConversation: Bool, upload: Upload? = nil) async {
        if state.ended && !opensConversation { return }
        let attachments = upload.map { [Attachment(url: $0.previewURL ?? URL(fileURLWithPath: "/"), kind: $0.kind, filename: $0.filename, mimeType: $0.mimeType)] } ?? []
        let local = input.isEmpty && upload == nil ? nil : ChatMessage(id: "local-\(UUID().uuidString)", author: .user, text: input, sentAt: Self.now(),
                                                                      delivery: .sending, attachments: attachments, pendingUpload: upload)
        if opensConversation && state.messages.isEmpty { state.startedHere = true }
        if let local { state.messages.append(local) }
        state.choices = []
        if opensConversation { state.entrypoints = [] }
        state.ended = false
        state.typing = true
        state.error = nil

        do {
            var media: MediaRef?
            if let upload { media = try await store(upload) }
            let response = try await api.turn(visitor: await visitor(), input: input, userInfo: pendingUserInfo, attributes: attributes, media: media)
            pendingUserInfo = nil
            if let replacement = response.replacementVisitor { adoptReplacementVisitor(replacement) }
            if let local {
                update(local.id) {
                    $0.delivery = .sent
                    $0.pendingUpload = nil
                }
            }
            await handle(response.result)
        } catch {
            let reason = (error as? APIError)?.message ?? Self.connectError
            state.typing = false
            if let local {
                update(local.id) {
                    $0.delivery = .failed
                    $0.error = reason
                }
            } else {
                state.error = reason
            }
        }
    }

    private func handle(_ result: TurnResult) async {
        switch result {
        case .skipped:
            state.typing = false
        case let .reply(terminal, message, choices, media):
            state.typing = false
            let hasReply = !(message ?? "").isEmpty || media != nil
            if hasReply {
                state.messages.append(ChatMessage(id: "bot-\(UUID().uuidString)", author: .bot, text: message ?? "", media: media, sentAt: Self.now()))
            }
            state.choices = terminal ? [] : choices
            state.ended = terminal
            if terminal && state.signedIn { state.pastConversations = true }
            if !terminal, hasReply, needsUserInfo("after_first_message"), state.messages.filter({ $0.author == .bot }).count == 1 {
                state.askForUserInfo = true
            }
            if terminal || !(cable?.connected ?? true) { await refreshCable(confirmCsat: terminal) }
        }
    }

    private func refreshCable(confirmCsat: Bool = false) async {
        guard let visitor = await visitor(), let history = try? await api.history(visitor: visitor) else { return }
        conversationID = history.conversationId ?? conversationID
        if confirmCsat { state.csat = csatPrompt(offered: history.csatOffered) }
        if history.status != "completed", let token = history.cableToken { cable?.subscribe(token) }
    }

    /// Brings the conversation on screen up to date with the server: the cable
    /// stops while the screen is closed, so replies sent meanwhile and the
    /// conversation ending are only in its history. Waits for a turn in
    /// flight, so its messages are already sent when the two are merged.
    private func catchUp() {
        enqueue { [weak self] in await self?.applyLatestHistory() }
    }

    private func applyLatestHistory() async {
        guard let visitor = await visitor(), let history = try? await api.history(visitor: visitor) else { return }
        guard !state.loading, let latestID = history.conversationId else { return }
        let replaced = conversationID.map { $0 != latestID } ?? false
        conversationID = latestID
        let completed = history.status == "completed"
        if replaced {
            // Another conversation took over while the screen was closed.
            state.signedIn = history.signedIn
            state.pastConversations = history.pastConversations
            show(history)
        } else {
            var next = state
            if !state.messages.isEmpty, !history.messages.isEmpty {
                let onPage = Set(history.messages.compactMap(\.historyId))
                let keptOlder = history.hasOlder && state.messages.contains { $0.historyId.map { !onPage.contains($0) } ?? false }
                next.messages = Self.mergeHistory(shown: state.messages, latest: history.messages, latestHasOlder: history.hasOlder)
                next.hasOlder = keptOlder ? state.hasOlder : history.hasOlder
            }
            // The ways in show over a conversation that had already ended.
            if completed && !state.ended && state.entrypoints.isEmpty {
                next.csat = csatPrompt(offered: history.csatOffered)
                next.ended = true
                next.choices = []
                if state.signedIn { next.pastConversations = true }
            }
            state = next
        }
        // Live updates follow only while the screen shows; opening it catches up again.
        if completed {
            cable?.stop()
        } else if inView, let token = history.cableToken {
            cable?.subscribe(token)
        }
    }

    /// The transcript on screen brought up to date with `latest`, the newest
    /// page of its history. A message the server holds appears once, as the
    /// server has it, and in the server's order. Earlier pages paged back to
    /// stay in front when the page does not reach them. A live reply the page
    /// does not have yet, because it arrived after the page was read, stays
    /// where it was relative to the page. What the visitor is still sending or
    /// failed to send stays last.
    nonisolated static func mergeHistory(shown: [ChatMessage], latest: [ChatMessage], latestHasOlder: Bool) -> [ChatMessage] {
        let onPage = Set(latest.compactMap(\.historyId))
        let liveOnPage = Set(latest.compactMap(\.platformId))
        let pageStart = shown.firstIndex { $0.historyId.map(onPage.contains) ?? false } ?? -1
        func liveOnly(_ message: ChatMessage) -> Bool {
            message.historyId == nil && message.delivery == .sent && message.platformId.map { !liveOnPage.contains($0) } ?? false
        }
        let indexed = shown.enumerated()
        let before = indexed.filter { i, m in
            (latestHasOlder && (m.historyId.map { !onPage.contains($0) } ?? false)) || (liveOnly(m) && i < pageStart)
        }.map(\.element)
        let after = indexed.filter { i, m in liveOnly(m) && i > pageStart }.map(\.element)
        return before + latest + after + shown.filter { $0.delivery != .sent }
    }

    private func onCableEvent(_ event: CableEvent) {
        switch event {
        case .agentMessage(let message):
            guard !state.messages.contains(where: { $0.id == message.id || $0.platformId == message.platformId }) else { return }
            state.messages.append(message)
            state.typing = false
        case let .conversationClosed(id, offered):
            guard id == nil || id == conversationID else { return }
            cable?.stop()
            state.ended = true
            state.choices = []
            state.csat = csatPrompt(offered: offered)
            if state.signedIn { state.pastConversations = true }
        }
    }

    /// Uploads the file under the visitor sending it. A token the server refuses
    /// has lapsed, and nothing on this side can tell; the visitor carries on as
    /// a fresh anonymous one, the way a refused turn hands them.
    private func store(_ upload: Upload) async throws -> MediaRef {
        guard let visitor = await visitor() else { throw APIError(status: 0, code: nil, message: Self.connectError) }
        do {
            return try await api.uploadMedia(visitor: visitor, upload: upload)
        } catch let error as APIError where error.status == 401 {
            let fresh = try await api.anonymousVisitor()
            adoptReplacementVisitor(fresh)
            return try await api.uploadMedia(visitor: fresh, upload: upload)
        }
    }

    private func csatPrompt(offered: Bool) -> CsatPrompt? {
        offered && state.config.csat.enabled ? .asking(question: state.config.csat.question ?? "How did we do?") : nil
    }

    private func update(_ id: String, _ change: (inout ChatMessage) -> Void) {
        guard let index = state.messages.firstIndex(where: { $0.id == id }) else { return }
        change(&state.messages[index])
    }

    /// The server refused the visitor it was shown and minted another. For a
    /// signed-in user that means their session lapsed: they carry on as the
    /// anonymous visitor until the app signs them in again.
    private func adoptReplacementVisitor(_ token: String) {
        if identity.signedInVisitor != nil {
            identity.signedInVisitor = nil
            identity.signedInUserID = nil
        }
        identity.anonymousVisitor = token
    }

    private static func now() -> String { ISO8601DateFormatter().string(from: Date()) }

    private func visitor() async -> String? {
        if let visitor = identity.signedInVisitor ?? identity.anonymousVisitor { return visitor }
        guard let minted = try? await api.anonymousVisitor() else { return nil }
        identity.anonymousVisitor = minted
        return minted
    }
}
