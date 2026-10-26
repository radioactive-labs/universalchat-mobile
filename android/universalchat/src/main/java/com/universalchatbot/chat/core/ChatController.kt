package com.universalchatbot.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What the chat screen shows. */
data class ChatState(
    val config: ChatConfig = ChatConfig.EMPTY,
    val loading: Boolean = true,
    /** The deployment is switched off, or not on the workspace's plan. */
    val unavailable: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val choices: List<Choice> = emptyList(),
    /** Ways into the flow, shown while no conversation is running. */
    val entrypoints: List<Entrypoint> = emptyList(),
    val typing: Boolean = false,
    val ended: Boolean = false,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val askForUserInfo: Boolean = false,
    /** Whether the conversation on screen was started in this session, rather than resumed. */
    val startedHere: Boolean = false,
    val csat: CsatPrompt? = null,
    val error: String? = null,
    val signedIn: Boolean = false,
    /** Offer the visitor their earlier conversations. Signed-in visitors only. */
    val pastConversations: Boolean = false,
    /** The list of earlier conversations, while it is open over the chat. */
    val past: PastConversations? = null,
)

/** A signed-in visitor looking back: their list, and the one they opened from it, read-only. */
data class PastConversations(
    val conversations: List<ConversationSummary> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val open: ConversationSummary? = null,
    val transcript: List<ChatMessage> = emptyList(),
    val transcriptHasOlder: Boolean = false,
    val transcriptLoading: Boolean = false,
)

sealed interface CsatPrompt {
    data class Asking(val question: String) : CsatPrompt
    data class Thanked(val thankYou: String) : CsatPrompt
}

/**
 * One deployment's conversation for the current visitor: resumes it, runs
 * turns, follows the cable while open, and asks for a rating when one is due.
 * Mirrors the website widget's behaviour so a conversation reads the same on
 * either.
 */
class ChatController(
    private val api: ApiClient,
    private val identity: IdentityStore,
    private val scope: CoroutineScope,
    cableFactory: (onEvent: (CableEvent) -> Unit, onRejected: () -> Unit) -> CableClient?,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val turnLock = Mutex()
    private var conversationId: Long? = null
    private var pendingUserInfo: Map<String, String>? = null
    private var pendingConversation: Long? = null

    /**
     * Custom attributes the app set about the visitor, sent with every turn.
     * Strings, numbers and booleans; anything else is sent as text.
     */
    @Volatile
    var attributes: Map<String, Any?> = emptyMap()
    private var opened = false

    // Whether the chat screen is showing, and the pending report of what the
    // visitor has read there. Reported at most every few seconds.
    @Volatile
    private var inView = false
    private var readReport: Job? = null
    private var lastReadReport = 0L

    private val cable: CableClient? = cableFactory(
        { event -> scope.launch { onCableEvent(event) } },
        { scope.launch { refreshCable() } },
    )

    init {
        // Each reply of ours that arrives while the screen is showing is read.
        scope.launch {
            state.map { s -> s.messages.count { it.author != Author.USER } }.distinctUntilChanged().collect { reportRead() }
        }
    }

    /**
     * Called when the chat screen opens. Loads the conversation once per
     * controller, and on every later opening catches up on what arrived
     * while it was closed.
     */
    fun open() {
        inView = true
        reportRead()
        if (opened) {
            catchUp()
            return
        }
        opened = true
        scope.launch { load() }
    }

    /** Called when the chat screen closes; live updates stop until it opens again. */
    fun close() {
        inView = false
        readReport?.cancel()
        cable?.stop()
    }

    /**
     * Tells the server the visitor has read what we sent, while the screen is
     * showing. Best effort: a failure only leaves our replies showing as
     * sent in the inbox a little longer.
     */
    private fun reportRead() {
        if (!inView || _state.value.messages.none { it.author != Author.USER }) return
        readReport?.cancel()
        readReport = scope.launch {
            delay((lastReadReport + READ_REPORT_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0))
            lastReadReport = System.currentTimeMillis()
            val visitor = visitor() ?: return@launch
            runCatching { api.markRead(visitor, conversationId) }
        }
    }

    /** Sends what the visitor typed, with the file they attached, if any. */
    fun send(text: String, upload: Upload? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && upload == null) return
        upload?.let(Uploads::refusal)?.let { refusal ->
            _state.update { it.copy(error = refusal) }
            return
        }
        scope.launch { runTurn(trimmed, opensConversation = false, upload = upload) }
    }

    fun choose(choice: Choice) = send(choice.label)

    fun start(entrypoint: Entrypoint) {
        scope.launch { runTurn(entrypoint.label, opensConversation = true) }
    }

    /** None of the ways in fit: start the flow from its beginning. */
    fun startWithoutEntrypoint() {
        scope.launch { runTurn("", opensConversation = true) }
    }

    fun retry(message: ChatMessage) {
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == message.id }) }
        send(message.text, message.pendingUpload)
    }

    /** A finished conversation starts over: at the ways in when the flow has them, otherwise straight away. */
    fun restart() {
        conversationId = null
        cable?.stop()
        _state.update { it.copy(messages = emptyList(), choices = emptyList(), ended = false, csat = null, hasOlder = false, startedHere = false) }
        scope.launch { begin() }
    }

    fun submitUserInfo(info: Map<String, String>) {
        identity.userInfoCollected = true
        pendingUserInfo = info.filterValues { it.isNotBlank() }
        val starting = _state.value.messages.isEmpty() && !_state.value.ended
        _state.update { it.copy(askForUserInfo = false) }
        if (starting) scope.launch { runTurn("", opensConversation = true) }
    }

    fun dismissUserInfo() {
        identity.userInfoCollected = true
        val starting = _state.value.messages.isEmpty()
        _state.update { it.copy(askForUserInfo = false) }
        if (starting) scope.launch { runTurn("", opensConversation = true) }
    }

    fun rate(score: Int) {
        scope.launch {
            val visitor = visitor() ?: return@launch
            try {
                val thanks = api.rate(visitor, conversationId, score)
                val text = thanks ?: _state.value.config.csat.thankYou ?: "Thanks for your feedback."
                _state.update { it.copy(csat = CsatPrompt.Thanked(text)) }
            } catch (e: Exception) {
                _state.update { it.copy(csat = null) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun loadOlder() {
        val oldest = _state.value.messages.firstOrNull { it.historyId != null }?.historyId ?: return
        if (_state.value.loadingOlder || !_state.value.hasOlder) return
        scope.launch {
            val visitor = visitor() ?: return@launch
            _state.update { it.copy(loadingOlder = true) }
            try {
                val page = api.history(visitor, beforeId = oldest)
                _state.update { s -> s.copy(messages = page.messages + s.messages, hasOlder = page.hasOlder, loadingOlder = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /** Opens the signed-in visitor's list of earlier conversations over the chat. */
    fun showPastConversations() {
        _state.update { it.copy(past = PastConversations()) }
        scope.launch { fetchPastConversations(beforeId = null) }
    }

    fun loadMorePastConversations() {
        val past = _state.value.past ?: return
        if (past.loading || !past.hasMore) return
        updatePast { it.copy(loading = true) }
        scope.launch { fetchPastConversations(beforeId = past.conversations.lastOrNull()?.id) }
    }

    /** The conversation still running is the chat underneath, so picking it just closes the list. */
    fun openPastConversation(conversation: ConversationSummary) {
        if (!conversation.ended && conversation.id == conversationId) return hidePastConversations()
        updatePast { it.copy(open = conversation, transcript = emptyList(), transcriptHasOlder = false, transcriptLoading = true) }
        scope.launch { fetchTranscript(conversation, beforeId = null) }
    }

    fun loadOlderInPastConversation() {
        val past = _state.value.past ?: return
        val open = past.open ?: return
        val oldest = past.transcript.firstOrNull { it.historyId != null }?.historyId ?: return
        if (past.transcriptLoading || !past.transcriptHasOlder) return
        updatePast { it.copy(transcriptLoading = true) }
        scope.launch { fetchTranscript(open, beforeId = oldest) }
    }

    /** Back from a transcript to the list. */
    fun closePastConversation() = updatePast { it.copy(open = null, transcript = emptyList()) }

    fun hidePastConversations() = _state.update { it.copy(past = null) }

    private fun updatePast(change: (PastConversations) -> PastConversations) =
        _state.update { s -> s.past?.let { s.copy(past = change(it)) } ?: s }

    private suspend fun fetchPastConversations(beforeId: Long?) {
        val visitor = visitor() ?: return updatePast { it.copy(loading = false, failed = true) }
        try {
            val page = api.conversations(visitor, beforeId)
            updatePast {
                it.copy(
                    conversations = if (beforeId == null) page.conversations else it.conversations + page.conversations,
                    hasMore = page.hasMore, loading = false, failed = false,
                )
            }
        } catch (e: Exception) {
            updatePast { it.copy(loading = false, failed = true) }
        }
    }

    private suspend fun fetchTranscript(conversation: ConversationSummary, beforeId: Long?) {
        val visitor = visitor() ?: return updatePast { it.copy(transcriptLoading = false) }
        try {
            val page = api.history(visitor, beforeId = beforeId, conversationId = conversation.id)
            updatePast {
                if (it.open?.id != conversation.id) it
                else it.copy(transcript = page.messages + it.transcript, transcriptHasOlder = page.hasOlder, transcriptLoading = false)
            }
        } catch (e: Exception) {
            updatePast { it.copy(transcriptLoading = false) }
        }
    }

    /** The visitor changed (signed in or out): drop what is on screen and load theirs. */
    fun reset() {
        cable?.stop()
        conversationId = null
        pendingUserInfo = null
        opened = false
        pendingConversation = null
        _state.value = ChatState()
    }

    /**
     * Opens on [conversationId], the conversation a tapped notification was
     * about. While it is still running it is the chat itself; once it has
     * ended, a signed-in visitor sees it from their past conversations. An
     * anonymous visitor has no past conversations, so they get the chat.
     */
    fun openConversation(conversationId: Long) {
        if (opened && !_state.value.loading) scope.launch { showConversation(conversationId, justLoaded = false) }
        else pendingConversation = conversationId
    }

    /** The notification tapped while the chat was loading, once it has loaded. */
    private suspend fun showPendingConversation() {
        val id = pendingConversation ?: return
        pendingConversation = null
        showConversation(id, justLoaded = true)
    }

    /** [justLoaded] when the conversation on screen was fetched a moment ago, so there is nothing to catch up on. */
    private suspend fun showConversation(id: Long, justLoaded: Boolean) {
        // The notification may be about a reply that arrived while the chat was closed.
        if (id == conversationId) {
            if (!justLoaded) catchUp()
            return
        }
        if (!_state.value.signedIn) return
        // Only one conversation runs at a time, and it is the one on screen, so this one has ended.
        val placeholder = ConversationSummary(id, ended = true, startedAt = null, lastAuthor = Author.BOT, lastAuthorName = null, lastText = "", lastHasAttachment = false, lastSentAt = null)
        _state.update { it.copy(past = PastConversations(open = placeholder, transcriptLoading = true)) }
        scope.launch { fetchPastConversations(beforeId = null) }
        fetchTranscript(placeholder, beforeId = null)
        // Its title names the day it started, which only the transcript knows.
        updatePast { past ->
            val startedAt = past.transcript.firstOrNull()?.sentAt
            if (past.open?.id == id) past.copy(open = past.open.copy(startedAt = startedAt)) else past
        }
    }

    private suspend fun load() {
        loadConversation()
        showPendingConversation()
    }

    private suspend fun loadConversation() {
        val config = try {
            api.config()
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = CONNECT_ERROR) }
            opened = false
            return
        }
        if (config == null) {
            _state.update { it.copy(loading = false, unavailable = true) }
            return
        }
        _state.update { it.copy(config = config) }

        val visitor = visitor()
        val history = visitor?.let { runCatching { api.history(it) }.getOrNull() }
        if (history != null) _state.update { it.copy(signedIn = history.signedIn, pastConversations = history.pastConversations) }
        if (history != null && history.messages.isNotEmpty()) {
            conversationId = history.conversationId
            history.cableToken?.let { cable?.subscribe(it) }
            showHistory(history)
            return
        }

        _state.update { it.copy(loading = false) }
        begin()
    }

    /**
     * Shows [history] as the conversation on screen, in place of whatever was
     * there. One that has ended offers the ways in when the flow has them,
     * and otherwise stays readable with its rating.
     */
    private fun showHistory(history: History) {
        val config = _state.value.config
        _state.update {
            val cleared = it.copy(loading = false, typing = false, startedHere = false, entrypoints = emptyList(), choices = emptyList(), csat = null)
            when {
                history.status == "completed" && config.entrypoints.isNotEmpty() ->
                    cleared.copy(messages = emptyList(), hasOlder = false, ended = false, entrypoints = config.entrypoints)
                history.status == "completed" ->
                    cleared.copy(
                        messages = history.messages, hasOlder = history.hasOlder, ended = true,
                        csat = csatPrompt(history.csatOffered, config),
                    )
                else ->
                    cleared.copy(messages = history.messages, hasOlder = history.hasOlder, ended = false, choices = history.lastChoices)
            }
        }
    }

    /** No conversation yet: offer the ways in, ask for details first, or start the flow. */
    private suspend fun begin() {
        val config = _state.value.config
        when {
            config.entrypoints.isNotEmpty() -> _state.update { it.copy(entrypoints = config.entrypoints) }
            needsUserInfo("before_chat") -> _state.update { it.copy(askForUserInfo = true) }
            else -> runTurn("", opensConversation = true)
        }
    }

    // A signed-in user's details come from the customer's server, so they are never asked for.
    private fun needsUserInfo(moment: String) =
        _state.value.config.collectUserInfo == moment && !identity.userInfoCollected && identity.signedInVisitor == null

    private suspend fun runTurn(input: String, opensConversation: Boolean, upload: Upload? = null) = turnLock.withLock {
        if (_state.value.ended && !opensConversation) return@withLock
        val local = if (input.isNotEmpty() || upload != null) {
            ChatMessage(
                id = "local-${UUID.randomUUID()}", author = Author.USER, text = input, sentAt = now(), delivery = Delivery.SENDING,
                attachments = listOfNotNull(upload?.let { Attachment(it.previewUri.orEmpty(), it.kind, it.filename, it.mimeType) }),
                pendingUpload = upload,
            )
        } else null

        _state.update { s ->
            s.copy(
                messages = if (local != null) s.messages + local else s.messages,
                choices = emptyList(),
                entrypoints = if (opensConversation) emptyList() else s.entrypoints,
                startedHere = s.startedHere || (opensConversation && s.messages.isEmpty()),
                ended = false,
                typing = true,
                error = null,
            )
        }

        try {
            val media = upload?.let { store(it) }
            val response = api.turn(visitor(), input, pendingUserInfo, attributes, media)
            pendingUserInfo = null
            response.replacementVisitor?.let(::adoptReplacementVisitor)
            markSent(local)
            handleReply(response.result)
        } catch (e: Exception) {
            val reason = (e as? ApiException)?.message ?: CONNECT_ERROR
            _state.update { s ->
                s.copy(
                    typing = false,
                    messages = s.messages.map { if (it.id == local?.id) it.copy(delivery = Delivery.FAILED, error = reason) else it },
                    error = if (local == null) reason else null,
                )
            }
        }
    }

    private suspend fun handleReply(result: TurnResult) {
        when (result) {
            TurnResult.Skipped -> _state.update { it.copy(typing = false) }
            is TurnResult.Reply -> {
                val reply = if (!result.message.isNullOrBlank() || result.media != null) {
                    ChatMessage(id = "bot-${UUID.randomUUID()}", author = Author.BOT, text = result.message.orEmpty(), media = result.media, sentAt = now())
                } else null
                _state.update { s ->
                    s.copy(
                        typing = false,
                        messages = if (reply != null) s.messages + reply else s.messages,
                        choices = if (result.terminal) emptyList() else result.choices,
                        ended = result.terminal,
                        pastConversations = s.pastConversations || (result.terminal && s.signedIn),
                    )
                }
                if (!result.terminal && reply != null && needsUserInfo("after_first_message") &&
                    _state.value.messages.count { it.author == Author.BOT } == 1
                ) {
                    _state.update { it.copy(askForUserInfo = true) }
                }
                if (result.terminal || !(cable?.connected ?: true)) refreshCable(confirmCsat = result.terminal)
            }
        }
    }

    private suspend fun refreshCable(confirmCsat: Boolean = false) {
        val visitor = visitor() ?: return
        val history = runCatching { api.history(visitor) }.getOrNull() ?: return
        conversationId = history.conversationId ?: conversationId
        if (confirmCsat) _state.update { it.copy(csat = csatPrompt(history.csatOffered, it.config)) }
        if (history.status != "completed") history.cableToken?.let { cable?.subscribe(it) }
    }

    /**
     * Brings the conversation on screen up to date with the server: the
     * cable stops while the screen is closed, so replies sent meanwhile and
     * the conversation ending are only in its history. Waits for a turn in
     * flight, so its messages are already sent when the two are merged.
     */
    private fun catchUp() {
        scope.launch {
            turnLock.withLock {
                val visitor = visitor() ?: return@withLock
                val history = runCatching { api.history(visitor) }.getOrNull() ?: return@withLock
                if (_state.value.loading || history.conversationId == null) return@withLock
                val replaced = conversationId != null && history.conversationId != conversationId
                conversationId = history.conversationId
                val completed = history.status == "completed"
                if (replaced) {
                    // Another conversation took over while the screen was closed.
                    _state.update { it.copy(signedIn = history.signedIn, pastConversations = history.pastConversations) }
                    showHistory(history)
                } else {
                    _state.update { s ->
                        val merging = s.messages.isNotEmpty() && history.messages.isNotEmpty()
                        val onPage = history.messages.mapNotNullTo(HashSet()) { it.historyId }
                        val keptOlder = history.hasOlder && s.messages.any { it.historyId != null && it.historyId !in onPage }
                        // The ways in show over a conversation that had already ended.
                        val ending = completed && !s.ended && s.entrypoints.isEmpty()
                        s.copy(
                            messages = if (merging) mergeHistory(s.messages, history.messages, history.hasOlder) else s.messages,
                            hasOlder = if (!merging || keptOlder) s.hasOlder else history.hasOlder,
                            ended = s.ended || ending,
                            choices = if (ending) emptyList() else s.choices,
                            csat = if (ending) csatPrompt(history.csatOffered, s.config) else s.csat,
                            pastConversations = s.pastConversations || (ending && s.signedIn),
                        )
                    }
                }
                // Live updates follow only while the screen shows; opening it catches up again.
                if (completed) cable?.stop() else if (inView) history.cableToken?.let { cable?.subscribe(it) }
            }
        }
    }

    private fun onCableEvent(event: CableEvent) {
        when (event) {
            is CableEvent.AgentMessage -> _state.update { s ->
                if (s.messages.any { it.id == event.message.id || it.platformId == event.message.platformId }) s
                else s.copy(messages = s.messages + event.message, typing = false)
            }
            is CableEvent.ConversationClosed -> if (event.conversationId == null || event.conversationId == conversationId) {
                cable?.stop()
                _state.update { it.copy(ended = true, choices = emptyList(), csat = csatPrompt(event.csatOffered, it.config), pastConversations = it.pastConversations || it.signedIn) }
            }
        }
    }

    /**
     * Uploads the file under the visitor sending it. A token the server
     * refuses has lapsed, and nothing on this side can tell; the visitor
     * carries on as a fresh anonymous one, the way a refused turn hands them.
     */
    private suspend fun store(upload: Upload): MediaRef {
        val bytes = upload.read()
        return try {
            api.uploadMedia(visitor() ?: throw ApiException(0, null, CONNECT_ERROR), upload.filename, upload.mimeType, bytes)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            val fresh = api.anonymousVisitor()
            adoptReplacementVisitor(fresh)
            api.uploadMedia(fresh, upload.filename, upload.mimeType, bytes)
        }
    }

    private fun csatPrompt(offered: Boolean, config: ChatConfig): CsatPrompt? =
        if (offered && config.csat.enabled) CsatPrompt.Asking(config.csat.question ?: "How did we do?") else null

    private fun markSent(local: ChatMessage?) {
        if (local == null) return
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == local.id) it.copy(delivery = Delivery.SENT, pendingUpload = null) else it }) }
    }

    /**
     * The server refused the visitor it was shown and minted another. For a
     * signed-in user that means their session lapsed: they carry on as the
     * anonymous visitor until the app signs them in again.
     */
    private fun adoptReplacementVisitor(token: String) {
        if (identity.signedInVisitor != null) {
            identity.signedInVisitor = null
            identity.signedInUserId = null
        }
        identity.anonymousVisitor = token
    }

    private suspend fun visitor(): String? {
        identity.signedInVisitor?.let { return it }
        identity.anonymousVisitor?.let { return it }
        return runCatching { api.anonymousVisitor() }.getOrNull()?.also { identity.anonymousVisitor = it }
    }

    private fun now() = java.time.Instant.now().toString()

    companion object {
        /**
         * The transcript on screen brought up to date with [latest], the
         * newest page of its history. A message the server holds appears once,
         * as the server has it, and in the server's order. Earlier pages paged
         * back to stay in front when the page does not reach them. A live
         * reply the page does not have yet, because it arrived after the page
         * was read, stays where it was relative to the page. What the visitor
         * is still sending or failed to send stays last.
         */
        internal fun mergeHistory(shown: List<ChatMessage>, latest: List<ChatMessage>, latestHasOlder: Boolean): List<ChatMessage> {
            val onPage = latest.mapNotNullTo(HashSet()) { it.historyId }
            val liveOnPage = latest.mapNotNullTo(HashSet()) { it.platformId }
            val pageStart = shown.indexOfFirst { it.historyId != null && it.historyId in onPage }
            fun liveOnly(message: ChatMessage) = message.historyId == null && message.delivery == Delivery.SENT &&
                message.platformId != null && message.platformId !in liveOnPage
            val before = shown.filterIndexed { i, m ->
                (latestHasOlder && m.historyId != null && m.historyId !in onPage) || (liveOnly(m) && i < pageStart)
            }
            val after = shown.filterIndexed { i, m -> liveOnly(m) && i > pageStart }
            return before + latest + after + shown.filter { it.delivery != Delivery.SENT }
        }

        const val CONNECT_ERROR = "Couldn't reach the chat service. Check your connection and try again."
        const val READ_REPORT_INTERVAL_MS = 3_000L
    }
}
