#if canImport(SwiftUI) && canImport(UIKit)
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UniversalChatCore

/// Bridges the controller's state into SwiftUI, with the visitor's theme choice and the deployment's font.
@MainActor
final class ChatModel: ObservableObject {
    @Published private(set) var state = ChatState()
    @Published var themeOverride: String? {
        didSet { UniversalChat.themeOverride = themeOverride }
    }
    @Published private(set) var fonts: WebFont?
    let controller: ChatController
    private var token: UUID?
    private var fontFamily: String?

    init(controller: ChatController) {
        self.controller = controller
        themeOverride = UniversalChat.themeOverride
        token = controller.observe { [weak self] state in
            self?.state = state
            self?.loadFont(state.config.style)
        }
    }

    deinit {
        if let token { Task { @MainActor [controller] in controller.removeObserver(token) } }
    }

    private func loadFont(_ style: WidgetStyle) {
        guard let family = style.fontFamily, let stylesheet = style.fontStylesheet, family != fontFamily else { return }
        fontFamily = family
        Task { fonts = await WebFont.load(family: family, stylesheet: stylesheet) }
    }

    /// The website widget's order: dark, then light, then back to the deployment's own.
    func toggleTheme() {
        switch themeOverride {
        case nil: themeOverride = "dark"
        case "dark": themeOverride = "light"
        default: themeOverride = nil
        }
    }
}

/// The full-screen chat. Opened with `UniversalChat.present(from:)`.
final class ChatViewController: UIHostingController<ChatView> {
    init() {
        let model = ChatModel(controller: UniversalChat.controller!)
        super.init(rootView: ChatView(model: model, onClose: {}))
        rootView = ChatView(model: model) { [weak self] in self?.dismiss(animated: true) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UniversalChat.chatVisible = true
        rootView.model.controller.open()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        UniversalChat.chatVisible = false
        rootView.model.controller.close()
    }
}

/// The chat screen, for an app that hosts it itself. Call `UniversalChat.configure` first.
@MainActor
public struct UniversalChatView: View {
    @StateObject private var model: ChatModel
    private let onClose: () -> Void

    public init(onClose: @escaping () -> Void) {
        _model = StateObject(wrappedValue: ChatModel(controller: UniversalChat.controller!))
        self.onClose = onClose
    }

    public var body: some View {
        ChatView(model: model, onClose: onClose)
            .onAppear {
                UniversalChat.chatVisible = true
                model.controller.open()
            }
            .onDisappear {
                UniversalChat.chatVisible = false
                model.controller.close()
            }
    }
}

/// The chat, drawn to the website widget's design and from the same widget
/// settings: header, conversation, quick replies under the input, and the
/// closing panel with its rating.
@MainActor
struct ChatView: View {
    @ObservedObject var model: ChatModel
    let onClose: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    private var state: ChatState { model.state }
    private var controller: ChatController { model.controller }

    private var dark: Bool {
        switch model.themeOverride {
        case "dark": return true
        case "light": return false
        default:
            switch state.config.style.theme {
            case .dark: return true
            case .light: return false
            case .system: return colorScheme == .dark
            }
        }
    }

    var body: some View {
        let look = ChatLook(style: state.config.style, dark: dark, fonts: model.fonts)
        ZStack {
            VStack(spacing: 0) {
                ChatHeader(
                    look: look, themeOverride: model.themeOverride, onToggleTheme: model.toggleTheme, onClose: onClose,
                    past: state.past,
                    onPastConversations: state.pastConversations ? { controller.showPastConversations() } : nil,
                    onBack: { state.past?.open != nil ? controller.closePastConversation() : controller.hidePastConversations() }
                )
                if let error = state.error { ErrorBanner(look: look, message: error, onDismiss: controller.dismissError) }
                // The past conversations cover the chat rather than replace
                // it, so a message half typed is still there on the way back.
                VStack(spacing: 0) {
                    ZStack {
                        look.background
                        if state.loading {
                            VStack(spacing: 12) {
                                ProgressView().tint(look.primary)
                                Text("Starting chat...").font(look.font(14, .medium)).foregroundColor(look.botText.opacity(0.7))
                            }
                        } else if state.unavailable {
                            Text("Chat isn't available right now.").font(look.font(look.small)).foregroundColor(look.surfaceText).padding(24)
                        } else {
                            Conversation(look: look, state: state, controller: controller)
                        }
                    }
                    if !state.loading && !state.unavailable {
                        if state.ended {
                            ClosingPanel(look: look, state: state, controller: controller)
                        } else {
                            InputBar(look: look, typing: state.typing, onError: { controller.showError($0) }) { text, upload in controller.send(text, upload: upload) }
                            if !state.choices.isEmpty && !state.typing { QuickReplies(look: look, choices: state.choices, onChoose: controller.choose) }
                        }
                    }
                }
                .overlay {
                    if let past = state.past { PastConversationsView(look: look, past: past, controller: controller) }
                }
            }
            .background(look.background.ignoresSafeArea())
            if state.askForUserInfo {
                UserInfoSheet(look: look, config: state.config, onSubmit: controller.submitUserInfo)
            }
        }
        .preferredColorScheme(dark ? .dark : .light)
        // The app may take a link for its own navigation; when it does, the
        // chat closes so the visitor lands where the link points.
        .environment(\.openURL, OpenURLAction { url in
            guard UniversalChat.linkHandler?(url) == true else { return .systemAction }
            onClose()
            return .handled
        })
    }
}

// MARK: - Header

/// The deployment's title and status, or while past conversations are open,
/// where the visitor is and the way back. The button to open them shows only
/// when there are some to look back on.
struct ChatHeader: View {
    let look: ChatLook
    let themeOverride: String?
    let onToggleTheme: () -> Void
    let onClose: () -> Void
    var past: PastConversations? = nil
    var onPastConversations: (() -> Void)? = nil
    var onBack: () -> Void = {}
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 12) {
            if let past {
                headerButton("arrow.left", label: past.open == nil ? "Back to chat" : "Back to past conversations", action: onBack)
                Text(pastConversationsTitle(past.open)).font(look.font(16, .semibold)).foregroundColor(.white).lineLimit(1)
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(look.title).font(look.font(16, .semibold)).foregroundColor(.white)
                    HStack(spacing: 4) {
                        Circle().fill(Color(red: 0.063, green: 0.725, blue: 0.506)).frame(width: 6, height: 6)
                            .scaleEffect(pulse ? 0.8 : 1).opacity(pulse ? 0.5 : 1)
                            .animation(.easeInOut(duration: 1).repeatForever(autoreverses: true), value: pulse)
                        Text(look.subtitle).font(look.font(12)).foregroundColor(.white.opacity(0.9))
                    }
                }
            }
            Spacer()
            if past == nil, let onPastConversations {
                headerButton("clock.arrow.circlepath", label: "Past conversations", action: onPastConversations)
            }
            headerButton(themeIcon, label: "Change theme", action: onToggleTheme)
            headerButton("xmark", label: "Close", action: onClose)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .background(look.header.ignoresSafeArea(edges: .top))
        .overlay(Rectangle().fill(Color.white.opacity(0.1)).frame(height: 1), alignment: .bottom)
        .onAppear { pulse = true }
    }

    private var themeIcon: String {
        switch themeOverride {
        case "dark": return "moon"
        case "light": return "sun.max"
        default: return "circle.lefthalf.filled"
        }
    }

    private func headerButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                .frame(width: 32, height: 32)
                .background(Color.white.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: look.radius * 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

struct ErrorBanner: View {
    let look: ChatLook
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        let text = look.dark ? Color(red: 0.988, green: 0.647, blue: 0.647) : Color(red: 0.6, green: 0.106, blue: 0.106)
        HStack(spacing: 12) {
            Text(message).font(look.font(look.small, .medium)).foregroundColor(text).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onDismiss) {
                Text("×").font(.system(size: 14, weight: .semibold)).foregroundColor(text)
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(Color.red.opacity(look.dark ? 0.2 : 0.1)))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(look.dark ? Color.red.opacity(0.1) : Color(red: 0.996, green: 0.886, blue: 0.886).opacity(0.95))
        .overlay(Rectangle().fill(Color.red.opacity(0.3)).frame(height: 1), alignment: .bottom)
    }
}

// MARK: - Conversation

struct Conversation: View {
    let look: ChatLook
    let state: ChatState
    let controller: ChatController

    var body: some View {
        GeometryReader { geometry in
            let maxBubble = geometry.size.width * 0.85
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if state.hasOlder {
                            Separator(look: look, text: state.loadingOlder ? "Loading…" : "Load earlier messages", action: controller.loadOlder)
                        } else if !state.messages.isEmpty {
                            Separator(look: look, text: state.startedHere ? "New conversation started" : "Start of conversation", action: nil)
                        }
                        if state.messages.isEmpty && !state.ended, let greeting = state.config.greeting {
                            HStack {
                                BubbleCard(look: look, background: look.botBackground, tailLeading: true, stripe: look.primary, border: look.border, maxWidth: maxBubble) {
                                    MessageText(look: look, text: greeting, color: look.botText)
                                }
                                Spacer(minLength: 0)
                            }
                        }
                        ForEach(state.messages) { message in
                            if message.author == .user {
                                UserBubble(look: look, message: message, maxWidth: maxBubble) { controller.retry(message) }
                            } else {
                                BotBubble(look: look, message: message, maxWidth: maxBubble)
                            }
                        }
                        if state.typing { TypingBubble(look: look) }
                        if !state.entrypoints.isEmpty {
                            FlowLayout(spacing: 8) {
                                ForEach(state.entrypoints, id: \.id) { entry in
                                    ChipButton(look: look, text: entry.label, radius: look.radius, size: 14) { controller.start(entry) }
                                }
                                ChipButton(look: look, text: "Something else", radius: look.radius, size: 14, action: controller.startWithoutEntrypoint)
                            }
                            .padding(.top, 12)
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(12)
                }
                .onChange(of: state.messages.count) { _ in withAnimation { proxy.scrollTo("bottom") } }
                .onChange(of: state.typing) { _ in withAnimation { proxy.scrollTo("bottom") } }
                .onAppear { proxy.scrollTo("bottom") }
            }
        }
    }
}

struct Separator: View {
    let look: ChatLook
    let text: String
    let action: (() -> Void)?

    var body: some View {
        let pill = Text(text).font(look.font(look.tiny, .medium)).foregroundColor(look.surfaceText)
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(Capsule().fill(look.surface))
            .overlay(Capsule().stroke(look.border, lineWidth: 1))
            .shadow(color: .black.opacity(0.08), radius: 1.5, y: 1)
            .opacity(0.8)
        Group {
            if let action { Button(action: action) { pill }.buttonStyle(.plain) } else { pill }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

/// A bubble's corners: rounded with the one nearest the sender squared off.
struct BubbleShape: Shape {
    let radius: CGFloat
    let tailLeading: Bool

    func path(in rect: CGRect) -> Path {
        let tail: CGFloat = 4
        let r = min(radius, rect.height / 2, rect.width / 2)
        let topLeft = tailLeading ? tail : r
        let topRight = tailLeading ? r : tail
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + topLeft, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - topRight, y: rect.minY))
        path.addArc(center: CGPoint(x: rect.maxX - topRight, y: rect.minY + topRight), radius: topRight, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addArc(center: CGPoint(x: rect.maxX - r, y: rect.maxY - r), radius: r, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addArc(center: CGPoint(x: rect.minX + r, y: rect.maxY - r), radius: r, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + topLeft))
        path.addArc(center: CGPoint(x: rect.minX + topLeft, y: rect.minY + topLeft), radius: topLeft, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}

/// A bubble's card: a soft shadow, and on the bot side a border and a coloured left edge.
struct BubbleCard<Content: View>: View {
    let look: ChatLook
    let background: Color
    let tailLeading: Bool
    let stripe: Color?
    let border: Color?
    let maxWidth: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = BubbleShape(radius: look.radius, tailLeading: tailLeading)
        content()
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minWidth: 160, alignment: .leading)
            .background(background)
            .overlay(alignment: .leading) { if let stripe { Rectangle().fill(stripe).frame(width: 2) } }
            .clipShape(shape)
            .overlay(border.map { shape.stroke($0, lineWidth: 1) })
            .shadow(color: .black.opacity(0.1), radius: 1.5, y: 1)
            .frame(maxWidth: maxWidth, alignment: tailLeading ? .leading : .trailing)
    }
}

struct BotBubble: View {
    let look: ChatLook
    let message: ChatMessage
    let maxWidth: CGFloat

    var body: some View {
        let agent = message.author == .agent
        // An agent's bubble carries the visitor-side colour so a person reads differently from the bot.
        let stripe = agent ? look.userBackground : look.border
        HStack {
            BubbleCard(look: look, background: look.botBackground, tailLeading: true, stripe: stripe, border: look.border, maxWidth: maxWidth) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(agent ? message.authorName ?? "Agent" : "Bot").font(look.font(look.tiny, .semibold))
                        // The border grey is too faint to read as text, so the bot's name takes the bubble's secondary text.
                        .foregroundColor(agent ? stripe : look.botTextSecondary).padding(.bottom, 4)
                    if !message.text.isEmpty { MessageText(look: look, text: message.text, color: look.botText) }
                    if let media = message.media { MediaView(look: look, media: media) }
                    ForEach(Array(message.attachments.enumerated()), id: \.offset) { _, attachment in AttachmentView(look: look, attachment: attachment, color: look.botText) }
                    if let time = formatTime(message.sentAt) {
                        Text(time).font(look.font(look.tiny, .medium)).foregroundColor(look.botTextSecondary)
                            .frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 6)
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }
}

struct UserBubble: View {
    let look: ChatLook
    let message: ChatMessage
    let maxWidth: CGFloat
    let onRetry: () -> Void

    var body: some View {
        let failed = message.delivery == .failed
        HStack {
            Spacer(minLength: 0)
            BubbleCard(look: look, background: look.userBackground, tailLeading: false, stripe: nil, border: nil, maxWidth: maxWidth) {
                VStack(alignment: .leading, spacing: 0) {
                    if !message.text.isEmpty { MessageText(look: look, text: message.text, color: look.userText) }
                    ForEach(Array(message.attachments.enumerated()), id: \.offset) { _, attachment in AttachmentView(look: look, attachment: attachment, color: look.userText) }
                    HStack(spacing: 8) {
                        if failed {
                            Button(action: onRetry) { Text("Retry").underline().foregroundColor(look.userTextSecondary) }.buttonStyle(.plain)
                            Text("Failed to send")
                        } else {
                            Text(formatTime(message.sentAt) ?? "")
                        }
                    }
                    .font(look.font(look.tiny, .medium))
                    .foregroundColor(look.userTextSecondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.top, 6)
                }
            }
            .opacity(failed ? 0.5 : 1)
        }
    }
}

// MARK: - Past conversations

/// A signed-in visitor's earlier conversations, over the chat: the list,
/// newest first, and the one they open from it, read-only, with the way back
/// to the chat at the foot. The header does the navigating.
struct PastConversationsView: View {
    let look: ChatLook
    let past: PastConversations
    let controller: ChatController

    var body: some View {
        VStack(spacing: 0) {
            if let open = past.open {
                transcript(open)
            } else {
                list
            }
        }
        .background(look.background.ignoresSafeArea())
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(past.conversations) { conversation in
                    Button { controller.openPastConversation(conversation) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(author(conversation)).lineLimit(1)
                                Spacer()
                                Text(conversation.ended ? formatWhen(conversation.lastSentAt) : "Ongoing")
                            }
                            .font(look.font(look.tiny)).foregroundColor(look.surfaceText)
                            Text(conversation.lastText.isEmpty ? (conversation.lastHasAttachment ? "Attachment" : "") : conversation.lastText)
                                .font(look.font(look.small)).foregroundColor(look.botText).lineLimit(1)
                        }
                        .padding(.horizontal, 16).padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .overlay(Rectangle().fill(look.border).frame(height: 1), alignment: .bottom)
                }
                if past.loading {
                    note("Loading…")
                } else if past.failed {
                    note("Couldn't load your conversations.")
                } else if past.conversations.isEmpty {
                    note("No past conversations yet.")
                } else if past.hasMore {
                    Separator(look: look, text: "Show older conversations", action: controller.loadMorePastConversations)
                }
            }
        }
    }

    private func transcript(_ open: ConversationSummary) -> some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                let maxBubble = geometry.size.width * 0.85
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if past.transcriptHasOlder {
                            Separator(look: look, text: past.transcriptLoading ? "Loading…" : "Load earlier messages", action: controller.loadOlderInPastConversation)
                        } else if !past.transcript.isEmpty {
                            Separator(look: look, text: "Start of conversation", action: nil)
                        }
                        ForEach(past.transcript) { message in
                            if message.author == .user {
                                UserBubble(look: look, message: message, maxWidth: maxBubble) {}
                            } else {
                                BotBubble(look: look, message: message, maxWidth: maxBubble)
                            }
                        }
                    }
                    .padding(12)
                }
            }
            VStack(spacing: 8) {
                Text(open.ended ? "This conversation has ended." : "This conversation is still open.")
                    .font(look.font(look.tiny)).foregroundColor(look.surfaceText)
                Button(action: controller.hidePastConversations) {
                    Text("Back to chat").font(look.font(look.small, .medium)).foregroundColor(look.userText)
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: look.radius).fill(look.userBackground))
                }
                .buttonStyle(.plain)
            }
            .padding(12)
            .overlay(Rectangle().fill(look.border).frame(height: 1), alignment: .top)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(look.font(look.tiny)).foregroundColor(look.surfaceText).frame(maxWidth: .infinity).padding(24)
    }

    private func author(_ conversation: ConversationSummary) -> String {
        switch conversation.lastAuthor {
        case .user: return "You"
        case .agent: return conversation.lastAuthorName ?? "Agent"
        case .bot: return "Bot"
        }
    }
}

func pastConversationsTitle(_ open: ConversationSummary?) -> String {
    guard let open else { return "Past conversations" }
    guard open.startedAt != nil else { return "Past conversation" }
    return "Conversation from \(formatDay(open.startedAt))"
}

private func parseDate(_ iso: String?) -> Date? {
    guard let iso else { return nil }
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
}

/// The time for today, the date otherwise.
private func formatWhen(_ iso: String?) -> String {
    guard let date = parseDate(iso) else { return "" }
    return Calendar.current.isDateInToday(date) ? (formatTime(iso) ?? "") : formatDay(iso)
}

private func formatDay(_ iso: String?) -> String {
    guard let date = parseDate(iso) else { return "" }
    let formatter = DateFormatter()
    formatter.setLocalizedDateFormatFromTemplate("MMM d")
    return formatter.string(from: date)
}

private func formatTime(_ iso: String?) -> String? {
    guard let iso else { return nil }
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let date = parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else { return nil }
    return DateFormatter.localizedString(from: date, dateStyle: .none, timeStyle: .short)
}

struct MessageText: View {
    let look: ChatLook
    let text: String
    let color: Color

    var body: some View {
        Text(markdown)
            .font(look.font(look.small))
            .foregroundColor(color)
            .lineSpacing(look.small * 0.35)
            .tint(look.link)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Bold, italic, code and links as the flow wrote them, with list items as bullets.
    private var markdown: AttributedString {
        let bulleted = text.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            let trimmed = line.drop { $0 == " " }
            for marker in ["- ", "* ", "+ "] where trimmed.hasPrefix(marker) {
                return String(line.prefix(line.count - trimmed.count)) + "• " + trimmed.dropFirst(2)
            }
            return String(line)
        }.joined(separator: "\n")
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        var result = (try? AttributedString(markdown: bulleted, options: options)) ?? AttributedString(text)
        for run in result.runs where run.link != nil {
            result[run.range].foregroundColor = look.link
            result[run.range].underlineStyle = .single
        }
        return result
    }
}

struct MediaView: View {
    let look: ChatLook
    let media: Media
    @Environment(\.openURL) private var openURL

    var body: some View {
        if media.type == "image" || media.type == "sticker" {
            AsyncImage(url: media.url) { image in
                image.resizable().scaledToFit()
            } placeholder: {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .padding(.top, 8)
            .onTapGesture { openURL(media.url) }
        } else {
            Button { openURL(media.url) } label: {
                Text(media.caption ?? "Open attachment").font(look.font(look.small)).foregroundColor(look.link).underline()
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
    }
}

/// A file on a message: an image shown inline, anything else as its name, opened on tap.
struct AttachmentView: View {
    let look: ChatLook
    let attachment: Attachment
    let color: Color
    @Environment(\.openURL) private var openURL

    var body: some View {
        if attachment.kind == "image" {
            Group {
                if attachment.url.isFileURL, let image = UIImage(contentsOfFile: attachment.url.path) {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    AsyncImage(url: attachment.url) { image in image.resizable().scaledToFit() } placeholder: {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .padding(.top, 8)
            .onTapGesture { if !attachment.url.isFileURL { openURL(attachment.url) } }
        } else {
            Button { if !attachment.url.isFileURL { openURL(attachment.url) } } label: {
                HStack(spacing: 6) {
                    Image(systemName: "doc").font(.system(size: 13))
                    Text(attachment.filename ?? "attachment").font(look.font(look.tiny)).lineLimit(1)
                }
                .foregroundColor(color)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(look.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
    }
}

struct TypingBubble: View {
    let look: ChatLook
    @State private var pulse = false

    var body: some View {
        HStack {
            HStack(spacing: 8) {
                Image(systemName: "ellipsis.message").font(.system(size: 18)).foregroundColor(look.botText).opacity(pulse ? 0.5 : 1)
                    .animation(.easeInOut(duration: 1).repeatForever(autoreverses: true), value: pulse)
                Text("Typing...").font(look.font(look.small)).foregroundColor(look.botText)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: look.radius).fill(look.botBackground))
            .overlay(RoundedRectangle(cornerRadius: look.radius).stroke(look.border, lineWidth: 1))
            .shadow(color: .black.opacity(0.1), radius: 1.5, y: 1)
            Spacer(minLength: 0)
        }
        .onAppear { pulse = true }
    }
}

// MARK: - Chips, input, quick replies

/// A reply chip: page-coloured with a border, filled with the visitor colour while pressed.
struct ChipButton: View {
    let look: ChatLook
    let text: String
    let radius: CGFloat
    var size: CGFloat? = nil
    var muted = false
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text(text).multilineTextAlignment(.leading) }
            .buttonStyle(ChipStyle(look: look, radius: radius, size: size ?? look.small, muted: muted))
    }
}

struct ChipStyle: ButtonStyle {
    let look: ChatLook
    let radius: CGFloat
    let size: CGFloat
    let muted: Bool
    var horizontal: CGFloat = 16
    var minSide: CGFloat = 0

    func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius)
        configuration.label
            .font(look.font(size, .medium))
            .foregroundColor(configuration.isPressed ? look.userText : (muted ? look.surfaceText : look.botText))
            .padding(.horizontal, horizontal)
            .padding(.vertical, minSide > 0 ? 0 : 8)
            .frame(minWidth: minSide, minHeight: minSide)
            .background(shape.fill(configuration.isPressed ? look.userBackground : look.background))
            .overlay(shape.stroke(look.border, lineWidth: 1))
    }
}

/// Wraps its children onto as many rows as they need.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: width.isFinite ? width : widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// The send and start-again buttons: visitor-coloured, the deployment's radius, an icon beside the label.
struct VisitorButton: View {
    let look: ChatLook
    let label: String
    let symbol: String
    let iconAfter: Bool
    var horizontal: CGFloat = 16
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if !iconAfter { Image(systemName: symbol).font(.system(size: 17, weight: .semibold)) }
                Text(label).font(look.font(look.base, .semibold))
                if iconAfter { Image(systemName: symbol).font(.system(size: 13, weight: .bold)) }
            }
            .foregroundColor(look.userText)
            .padding(.horizontal, horizontal)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: look.radius).fill(look.userBackground))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

struct InputBar: View {
    let look: ChatLook
    let typing: Bool
    let onError: (String) -> Void
    let onSend: (String, Upload?) -> Void
    @State private var draft = ""
    @State private var staged: Upload?
    @State private var choosingFile = false
    @State private var photo: PhotosPickerItem?
    @State private var choosingPhoto = false

    var body: some View {
        let canSend = (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || staged != nil) && !typing
        VStack(alignment: .leading, spacing: 8) {
        if let staged { StagedFile(look: look, upload: staged) { self.staged = nil } }
        HStack(spacing: 8) {
            Menu {
                Button { choosingPhoto = true } label: { Label("Photo Library", systemImage: "photo") }
                Button { choosingFile = true } label: { Label("Files", systemImage: "folder") }
            } label: {
                Image(systemName: "paperclip").font(.system(size: 17)).foregroundColor(look.surfaceText)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: look.radius).fill(look.surface))
                    .overlay(RoundedRectangle(cornerRadius: look.radius).stroke(look.border, lineWidth: 1))
            }
            .accessibilityLabel("Attach a file")
            ZStack(alignment: .leading) {
                if draft.isEmpty {
                    Text("Type your message...").font(look.font(look.small)).foregroundColor(look.surfaceText.opacity(0.7))
                }
                TextField("", text: $draft)
                    .font(look.font(look.small))
                    .foregroundColor(look.surfaceText)
                    .submitLabel(.send)
                    .onSubmit(send)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: look.radius).fill(look.surface))
            .overlay(RoundedRectangle(cornerRadius: look.radius).stroke(look.border, lineWidth: 1))
            VisitorButton(look: look, label: "Send", symbol: "arrow.right", iconAfter: true, enabled: canSend, action: send)
        }
        }
        .photosPicker(isPresented: $choosingPhoto, selection: $photo, matching: .any(of: [.images, .videos]))
        .onChange(of: photo) { item in
            guard let item else { return }
            photo = nil
            Task { await stagePhoto(item) }
        }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: Uploads.acceptedTypes.compactMap { UTType(mimeType: $0) }) { result in
            if case .success(let url) = result { stageFile(url) }
        }
        .padding(12)
        .background(look.background)
        .overlay(Rectangle().fill(look.border).frame(height: 1), alignment: .top)
    }

    private func send() {
        guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || staged != nil, !typing else { return }
        onSend(draft, staged)
        draft = ""
        staged = nil
    }

    /// A photo or video from the library. A photo in a format the server
    /// does not take, such as HEIC, goes as a JPEG. A video comes as a file,
    /// so one over the limit is refused before it is read.
    private func stagePhoto(_ item: PhotosPickerItem) async {
        if let type = item.supportedContentTypes.first(where: { $0.conforms(to: .movie) }) {
            await stageMovie(item, type: type)
            return
        }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            onError("That photo couldn't be read.")
            return
        }
        let type = item.supportedContentTypes.first
        if let mime = type?.preferredMIMEType, Uploads.acceptedTypes.contains(mime) {
            staged = Upload(filename: "photo.\(type?.preferredFilenameExtension ?? "jpg")", mimeType: mime, data: data, previewURL: Self.preview(data, mime))
        } else if let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.85) {
            staged = Upload(filename: "photo.jpg", mimeType: "image/jpeg", data: jpeg, previewURL: Self.preview(jpeg, "image/jpeg"))
        } else {
            onError("That type of file can't be sent here.")
        }
    }

    private func stageMovie(_ item: PhotosPickerItem, type: UTType) async {
        guard let movie = try? await item.loadTransferable(type: PickedMovie.self) else {
            onError("That video couldn't be read.")
            return
        }
        defer { try? FileManager.default.removeItem(at: movie.url) }
        guard let data = read(movie.url) else { return }
        let mime = type.preferredMIMEType ?? "video/mp4"
        staged = Upload(filename: "video.\(type.preferredFilenameExtension ?? "mp4")", mimeType: mime, data: data, previewURL: nil)
    }

    private func stageFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = read(url) else { return }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        staged = Upload(filename: url.lastPathComponent, mimeType: mime, data: data, previewURL: Self.preview(data, mime))
    }

    /// The file's bytes, or nil once the visitor has been told why not.
    private func read(_ url: URL) -> Data? {
        do {
            return try Uploads.read(fileAt: url)
        } catch Uploads.ReadError.tooLarge {
            onError(Uploads.tooLarge)
        } catch {
            onError("That file couldn't be read.")
        }
        return nil
    }

    /// A copy in temporary storage the bubble can show while the upload runs.
    private static func preview(_ data: Data, _ mime: String) -> URL? {
        guard mime.hasPrefix("image/") else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("universalchat-\(UUID().uuidString)")
        return (try? data.write(to: url)) != nil ? url : nil
    }
}

/// A video from the photo library as a file in temporary storage, so its
/// size can be checked before it is read.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent("universalchat-\(UUID().uuidString)")
                .appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}

/// The picked file, above the input until it is sent or removed.
struct StagedFile: View {
    let look: ChatLook
    let upload: Upload
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            if upload.kind == "image", let image = UIImage(data: upload.data) {
                Image(uiImage: image).resizable().scaledToFill().frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 4))
            } else {
                Image(systemName: "doc").font(.system(size: 17)).foregroundColor(look.surfaceText)
            }
            Text(upload.filename).font(look.font(look.tiny)).foregroundColor(look.surfaceText).lineLimit(1).frame(maxWidth: 160, alignment: .leading)
            Button(action: onRemove) { Image(systemName: "xmark").font(.system(size: 12, weight: .semibold)).foregroundColor(look.surfaceText) }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove attachment")
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(look.surface))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(look.border, lineWidth: 1))
    }
}

struct QuickReplies: View {
    let look: ChatLook
    let choices: [Choice]
    let onChoose: (Choice) -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Quick replies:").font(look.font(look.tiny, .medium)).foregroundColor(look.surfaceText).padding(.leading, 4).padding(.top, 8)
            FlowLayout(spacing: 8) {
                ForEach(expanded ? choices : Array(choices.prefix(5)), id: \.self) { choice in
                    ChipButton(look: look, text: choice.label, radius: look.radius * 0.75) { onChoose(choice) }
                }
                if choices.count > 5 {
                    ChipButton(look: look, text: expanded ? "Show less" : "+\(choices.count - 5) more", radius: look.radius * 0.75, muted: true) { expanded.toggle() }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(look.background)
        .overlay(Rectangle().fill(look.border).frame(height: 1), alignment: .top)
    }
}

// MARK: - Closing panel and rating

struct ClosingPanel: View {
    let look: ChatLook
    let state: ChatState
    let controller: ChatController

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "message").font(.system(size: 20)).foregroundColor(look.botText)
                .frame(width: 48, height: 48).background(Circle().fill(look.botText.opacity(0.08)))
            if let csat = state.csat { CsatPanel(look: look, prompt: csat, onRate: controller.rate) }
            VStack(spacing: 4) {
                Text("Thanks for chatting").font(look.font(look.small, .semibold)).foregroundColor(look.botText)
                Text("We're here whenever you need us. Start a new chat anytime.").font(look.font(look.tiny)).foregroundColor(look.surfaceText)
                    .multilineTextAlignment(.center)
            }
            VisitorButton(look: look, label: "Start a new chat", symbol: "message", iconAfter: false, horizontal: 24, enabled: true, action: controller.restart)
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(look.background)
        .overlay(Rectangle().fill(look.border).frame(height: 1), alignment: .top)
    }
}

struct CsatPanel: View {
    let look: ChatLook
    let prompt: CsatPrompt
    let onRate: (Int) -> Void

    var body: some View {
        VStack(spacing: 12) {
            switch prompt {
            case .asking(let question):
                Text(question).font(look.font(look.small, .semibold)).foregroundColor(look.botText).multilineTextAlignment(.center)
                HStack(spacing: 8) {
                    ForEach(1...5, id: \.self) { score in
                        Button("\(score)") { onRate(score) }
                            .buttonStyle(ChipStyle(look: look, radius: look.radius * 0.75, size: look.small, muted: false, horizontal: 12, minSide: 40))
                            .accessibilityLabel("\(score) out of 5")
                    }
                }
            case .thanked(let thanks):
                Text(thanks).font(look.font(look.small, .semibold)).foregroundColor(look.botText)
            }
        }
    }
}

// MARK: - Details form

/// The details form, one question at a time as the website widget asks them.
struct UserInfoSheet: View {
    let look: ChatLook
    let config: ChatConfig
    let onSubmit: ([String: String]) -> Void
    @State private var step = 0
    @State private var values: [String: String] = [:]

    var body: some View {
        let fields = config.userInfoFields
        let field = step < fields.count ? fields[step] : "name"
        let required = config.userInfoRequiredFields.contains(field)
        let value = values[field] ?? ""
        let last = step >= fields.count - 1
        let canContinue = !required || !value.trimmingCharacters(in: .whitespaces).isEmpty
        let copy = copy(for: field)

        ZStack {
            Color.black.opacity(0.3).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                GeometryReader { geometry in
                    look.primary.opacity(0.8).frame(width: geometry.size.width * CGFloat(step + 1) / CGFloat(max(fields.count, 1)), height: 2)
                }
                .frame(height: 2)
                VStack(alignment: .leading, spacing: 0) {
                    Text(copy.title).font(look.font(24, .semibold)).foregroundColor(look.botText)
                    Text(copy.subtitle).font(look.font(16)).foregroundColor(look.surfaceText).opacity(0.8).padding(.top, 8).padding(.bottom, 40)
                    TextField(copy.placeholder, text: Binding(get: { value }, set: { values[field] = $0 }))
                        .font(look.font(20))
                        .foregroundColor(look.botText)
                        .keyboardType(field == "email" ? UIKeyboardType.emailAddress : field == "phone" ? UIKeyboardType.phonePad : UIKeyboardType.default)
                        .textContentType(field == "email" ? UITextContentType.emailAddress : field == "phone" ? UITextContentType.telephoneNumber : UITextContentType.name)
                        .autocapitalization(field == "name" ? UITextAutocapitalizationType.words : UITextAutocapitalizationType.none)
                        .padding(.vertical, 12)
                        .overlay(Rectangle().fill(look.primary).frame(height: 2), alignment: .bottom)
                        .onSubmit { if canContinue { advance(last: last) } }
                    HStack(spacing: 16) {
                        if !required {
                            Button { advance(last: last) } label: {
                                Text("Skip").font(look.font(14, .medium)).foregroundColor(look.surfaceText)
                            }
                            .buttonStyle(.plain).opacity(0.6)
                                .padding(.horizontal, 20).padding(.vertical, 10)
                        }
                        Button { advance(last: last) } label: {
                            HStack(spacing: 8) {
                                Text(last ? "Start chatting" : "Continue").font(look.font(16, .medium))
                                Image(systemName: "arrow.right").font(.system(size: 13, weight: .bold))
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: look.radius * 0.5).fill(look.primary))
                        }
                        .buttonStyle(.plain)
                        .disabled(!canContinue)
                        .opacity(canContinue ? 1 : 0.5)
                    }
                    .padding(.top, 40)
                    HStack(spacing: 6) {
                        ForEach(fields.indices, id: \.self) { index in
                            Capsule().fill(index <= step ? look.primary : look.border.opacity(0.3)).frame(width: index == step ? 24 : 6, height: 4)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 40)
                }
                .padding(.horizontal, 40)
                .padding(.vertical, 48)
            }
            .frame(maxWidth: 448)
            .background(RoundedRectangle(cornerRadius: look.radius).fill(look.surface))
            .clipShape(RoundedRectangle(cornerRadius: look.radius))
            .shadow(color: .black.opacity(0.25), radius: 25, y: 12)
            .padding(16)
        }
    }

    private func advance(last: Bool) {
        if last { onSubmit(values) } else { step += 1 }
    }

    private func copy(for field: String) -> (title: String, subtitle: String, placeholder: String) {
        let name = values["name"] ?? ""
        switch field {
        case "name": return (step == 0 ? "Hey there 👋" : "Great!", "What should we call you?", "Your name")
        case "email": return (name.isEmpty ? "Great!" : "Great to meet you, \(name)", "What's your email?", "your@email.com")
        default: return ("Last thing...", "Your phone number?", "+1 (555) 000-0000")
        }
    }
}
#endif
