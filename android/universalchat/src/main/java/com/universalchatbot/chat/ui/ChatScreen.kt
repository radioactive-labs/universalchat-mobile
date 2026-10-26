package com.universalchatbot.chat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.universalchatbot.chat.core.Upload
import com.universalchatbot.chat.core.Uploads
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import com.universalchatbot.chat.core.ConversationSummary
import com.universalchatbot.chat.core.PastConversations
import com.universalchatbot.chat.core.Author
import com.universalchatbot.chat.core.ChatController
import com.universalchatbot.chat.core.ChatMessage
import com.universalchatbot.chat.core.ChatState
import com.universalchatbot.chat.core.CsatPrompt
import com.universalchatbot.chat.core.Delivery
import com.universalchatbot.chat.core.Markdown
import com.universalchatbot.chat.core.Media
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The chat, drawn to the website widget's design and from the same widget
 * settings: header, conversation, quick replies under the input, and the
 * closing panel with its rating.
 */
@Composable
internal fun ChatScreen(
    state: ChatState,
    controller: ChatController,
    themeOverride: String?,
    onToggleTheme: () -> Unit,
    onClose: () -> Unit,
    /** Opens the system file picker and hands back what the visitor chose. */
    pickFile: (onPicked: (Upload) -> Unit) -> Unit = {},
) {
    val look = LocalChatLook.current
    val c = look.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .imePadding()
    ) {
        Header(
            themeOverride, onToggleTheme, onClose,
            past = state.past,
            onPastConversations = if (state.pastConversations) controller::showPastConversations else null,
            onBack = { if (state.past?.open != null) controller.closePastConversation() else controller.hidePastConversations() },
        )
        state.error?.let { ErrorBanner(it, controller::dismissError) }
        // The past conversations cover the chat rather than replace it, so a
        // message half typed is still there on the way back.
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when {
                        state.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Starting chat...", color = c.botText.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
                        }
                        state.unavailable -> Text(
                            "Chat isn't available right now.",
                            Modifier.align(Alignment.Center).padding(24.dp),
                            color = c.surfaceText, fontSize = look.small, fontFamily = look.fontFamily,
                        )
                        else -> Conversation(state, controller)
                    }
                }
                if (!state.loading && !state.unavailable) {
                    Box(Modifier.navigationBarsPadding()) {
                        if (state.ended) ClosingPanel(state, controller) else Column {
                            InputBar(state, controller, pickFile)
                            if (state.choices.isNotEmpty() && !state.typing) QuickReplies(state, controller)
                        }
                    }
                }
            }
            state.past?.let { PastConversationsView(it, controller, Modifier.fillMaxSize().background(c.background)) }
        }
    }

    if (state.askForUserInfo) UserInfoSheet(state, controller)
}

/**
 * The deployment's title and status, or while past conversations are open,
 * where the visitor is and the way back. The button to open them shows only
 * when there are some to look back on.
 */
@Composable
private fun Header(
    themeOverride: String?,
    onToggleTheme: () -> Unit,
    onClose: () -> Unit,
    past: PastConversations? = null,
    onPastConversations: (() -> Unit)? = null,
    onBack: () -> Unit = {},
) {
    val look = LocalChatLook.current
    val pulse = rememberInfiniteTransition(label = "online")
    val dot by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier
            .fillMaxWidth()
            .background(look.colors.header)
            .statusBarsPadding()
            .drawBehind { drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (past != null) {
            HeaderButton(TablerIcon.ArrowLeft, if (past.open != null) "Back to past conversations" else "Back to chat", onBack)
            Text(
                pastConversationsTitle(past.open), Modifier.weight(1f),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, fontFamily = look.fontFamily,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(Modifier.weight(1f)) {
                Text(look.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, fontFamily = look.fontFamily)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).scale(0.8f + 0.2f * dot).alpha(dot).clip(CircleShape).background(Color(0xFF10B981)))
                    Text(look.subtitle, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontFamily = look.fontFamily)
                }
            }
            onPastConversations?.let { HeaderButton(TablerIcon.History, "Past conversations", it) }
        }
        val themeIcon = when (themeOverride) {
            "dark" -> TablerIcon.Moon
            "light" -> TablerIcon.Sun
            else -> TablerIcon.Contrast
        }
        HeaderButton(themeIcon, "Change theme", onToggleTheme)
        HeaderButton(TablerIcon.X, "Close", onClose)
    }
}

@Composable
private fun HeaderButton(icon: TablerIcon, description: String, onClick: () -> Unit) {
    val look = LocalChatLook.current
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(look.radius * 0.5f))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { TablerIconView(icon, Color.White, 18.dp) }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val look = LocalChatLook.current
    val background = if (look.dark) Color(0x1AEF4444) else Color(0xF2FEE2E2)
    val text = if (look.dark) Color(0xFFFCA5A5) else Color(0xFF991B1B)
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { drawLine(Color(0x4DEF4444), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, Modifier.weight(1f), color = text, fontSize = look.small, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Color(if (look.dark) 0x33EF4444 else 0x1AEF4444)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Conversation(state: ChatState, controller: ChatController) {
    val look = LocalChatLook.current
    val list = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.typing, state.entrypoints.size) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bubbleMax = maxWidth * 0.85f
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.hasOlder) {
                item("older") { Separator(if (state.loadingOlder) "Loading…" else "Load earlier messages", onClick = controller::loadOlder) }
            } else if (state.messages.isNotEmpty()) {
                item("start") { Separator(if (state.startedHere) "New conversation started" else "Start of conversation") }
            }
            if (state.messages.isEmpty() && !state.ended) {
                state.config.greeting?.let { greeting -> item("greeting") { Greeting(greeting, bubbleMax) } }
            }
            items(state.messages, key = { it.id }) { message ->
                if (message.author == Author.USER) {
                    UserBubble(message, maxWidth = bubbleMax) { controller.retry(message) }
                } else {
                    BotBubble(message, bubbleMax)
                }
            }
            if (state.typing) item("typing") { Typing() }
            if (state.entrypoints.isNotEmpty()) item("entrypoints") { Entrypoints(state, controller) }
        }
    }
}

/**
 * A signed-in visitor's earlier conversations, over the chat: the list,
 * newest first, and the one they open from it, read-only, with the way back
 * to the chat at the foot. The header does the navigating.
 */
@Composable
private fun PastConversationsView(past: PastConversations, controller: ChatController, modifier: Modifier) {
    val open = past.open
    BackHandler { if (open != null) controller.closePastConversation() else controller.hidePastConversations() }
    // Takes every touch, so none reaches the chat it covers.
    Column(modifier.fillMaxWidth().pointerInput(Unit) {}) {
        if (open == null) PastList(past, controller, Modifier.weight(1f)) else PastTranscript(past, open, controller, Modifier.weight(1f))
    }
}

@Composable
private fun PastList(past: PastConversations, controller: ChatController, modifier: Modifier) {
    val look = LocalChatLook.current
    val c = look.colors
    val muted = TextStyle(color = c.surfaceText, fontSize = look.tiny, fontFamily = look.fontFamily)
    LazyColumn(modifier.fillMaxWidth()) {
        items(past.conversations, key = { it.id }) { conversation ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { controller.openPastConversation(conversation) }
                    .drawBehind { drawLine(c.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val who = when (conversation.lastAuthor) {
                        Author.USER -> "You"
                        Author.AGENT -> conversation.lastAuthorName ?: "Agent"
                        Author.BOT -> "Bot"
                    }
                    Text(who, Modifier.weight(1f), style = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (conversation.ended) formatWhen(conversation.lastSentAt) else "Ongoing", style = muted)
                }
                Text(
                    conversation.lastText.ifEmpty { if (conversation.lastHasAttachment) "Attachment" else "" },
                    color = c.botText, fontSize = look.small, fontFamily = look.fontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            past.loading -> item("loading") { Text("Loading…", Modifier.fillMaxWidth().padding(24.dp), style = muted, textAlign = TextAlign.Center) }
            past.failed -> item("failed") { Text("Couldn't load your conversations.", Modifier.fillMaxWidth().padding(24.dp), style = muted, textAlign = TextAlign.Center) }
            past.conversations.isEmpty() -> item("empty") { Text("No past conversations yet.", Modifier.fillMaxWidth().padding(24.dp), style = muted, textAlign = TextAlign.Center) }
            past.hasMore -> item("more") { Separator("Show older conversations", onClick = controller::loadMorePastConversations) }
        }
    }
}

@Composable
private fun PastTranscript(past: PastConversations, open: ConversationSummary, controller: ChatController, modifier: Modifier) {
    val look = LocalChatLook.current
    val c = look.colors
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val bubbleMax = maxWidth * 0.85f
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (past.transcriptHasOlder) {
                    item("older") { Separator(if (past.transcriptLoading) "Loading…" else "Load earlier messages", onClick = controller::loadOlderInPastConversation) }
                } else if (past.transcript.isNotEmpty()) {
                    item("start") { Separator("Start of conversation") }
                }
                items(past.transcript, key = { it.id }) { message ->
                    if (message.author == Author.USER) UserBubble(message, maxWidth = bubbleMax) {} else BotBubble(message, bubbleMax)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(c.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (open.ended) "This conversation has ended." else "This conversation is still open.",
                color = c.surfaceText, fontSize = look.tiny, fontFamily = look.fontFamily,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(look.radius))
                    .background(c.userBackground)
                    .clickable(onClick = controller::hidePastConversations)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Back to chat", color = c.userText, fontSize = look.small, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
            }
        }
    }
}

internal fun pastConversationsTitle(open: ConversationSummary?) = when {
    open == null -> "Past conversations"
    open.startedAt == null -> "Past conversation"
    else -> "Conversation from ${formatDay(open.startedAt)}"
}

/** The time for today, the date otherwise. */
private fun formatWhen(iso: String?): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val zone = ZoneId.systemDefault()
    return if (instant.atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)) formatTime(iso).orEmpty() else formatDay(iso)
}

private fun formatDay(iso: String?): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault("")

@Composable
private fun Separator(text: String, onClick: (() -> Unit)? = null) {
    val look = LocalChatLook.current
    val c = look.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier
                .alpha(0.8f)
                .shadow(1.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.border, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = c.surfaceText, fontSize = look.tiny, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily,
        )
    }
}

/** A bubble's card: rounded with the corner nearest the sender squared off, a soft shadow, and on the bot side a border and a coloured left edge. */
@Composable
private fun BubbleCard(
    background: Color,
    tailLeft: Boolean,
    maxWidth: Dp,
    stripe: Color?,
    border: Color?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val look = LocalChatLook.current
    val shape: Shape = if (tailLeft) {
        RoundedCornerShape(topStart = 4.dp, topEnd = look.radius, bottomEnd = look.radius, bottomStart = look.radius)
    } else {
        RoundedCornerShape(topStart = look.radius, topEnd = 4.dp, bottomEnd = look.radius, bottomStart = look.radius)
    }
    Box(
        modifier
            .widthIn(min = 160.dp, max = maxWidth)
            .shadow(1.5.dp, shape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .then(if (stripe != null) Modifier.drawBehind { drawRect(stripe, size = size.copy(width = 2.dp.toPx())) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { content() }
}

@Composable
private fun Greeting(text: String, maxWidth: Dp) {
    val c = LocalChatLook.current.colors
    BubbleCard(c.botBackground, tailLeft = true, maxWidth = maxWidth, stripe = c.primary, border = c.border) {
        MessageText(text, c.botText)
    }
}

@Composable
private fun BotBubble(message: ChatMessage, maxWidth: Dp) {
    val look = LocalChatLook.current
    val c = look.colors
    val agent = message.author == Author.AGENT
    // An agent's bubble carries the visitor-side colour so a person reads
    // differently from the bot.
    val stripe = if (agent) c.userBackground else c.border
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        BubbleCard(c.botBackground, tailLeft = true, maxWidth = maxWidth, stripe = stripe, border = c.border) {
            Column {
                Text(
                    if (agent) message.authorName ?: "Agent" else "Bot",
                    Modifier.padding(bottom = 4.dp),
                    // The border grey is too faint to read as text, so the bot's name takes the bubble's secondary text.
                    color = if (agent) stripe else c.botTextSecondary, fontSize = look.tiny, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em, fontFamily = look.fontFamily,
                )
                if (message.text.isNotEmpty()) MessageText(message.text, c.botText)
                message.media?.let { MediaView(it, c.botTextSecondary) }
                message.attachments.forEach { AttachmentView(it, c.botText) }
                Timestamp(message.sentAt, c.botTextSecondary)
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage, maxWidth: Dp, onRetry: () -> Unit) {
    val look = LocalChatLook.current
    val c = look.colors
    val failed = message.delivery == Delivery.FAILED
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        BubbleCard(
            c.userBackground, tailLeft = false, maxWidth = maxWidth, stripe = null, border = null,
            modifier = Modifier.alpha(if (failed) 0.5f else 1f),
        ) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                if (message.text.isNotEmpty()) MessageText(message.text, c.userText)
                message.attachments.forEach { AttachmentView(it, c.userText) }
                Row(Modifier.align(Alignment.End).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val style = TextStyle(color = c.userTextSecondary, fontSize = look.tiny, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
                    if (failed) {
                        Text("Retry", Modifier.clickable(onClick = onRetry), style = style.copy(textDecoration = TextDecoration.Underline))
                        Text("Failed to send", style = style)
                    } else {
                        Text(formatTime(message.sentAt).orEmpty(), style = style)
                    }
                }
            }
        }
    }
}

@Composable
private fun Timestamp(sentAt: String?, color: Color) {
    val look = LocalChatLook.current
    val time = formatTime(sentAt) ?: return
    Text(
        time,
        Modifier.fillMaxWidth().padding(top = 6.dp),
        color = color, fontSize = look.tiny, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, fontFamily = look.fontFamily,
    )
}

private fun formatTime(iso: String?): String? = runCatching {
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrNull()

@Composable
private fun MessageText(text: String, color: Color) {
    val look = LocalChatLook.current
    val uri = LocalUriHandler.current
    val annotated = remember(text, look.colors) { markdown(text, look.colors) }
    ClickableText(
        text = annotated,
        style = TextStyle(color = color, fontSize = look.small, lineHeight = look.small * 1.6f, fontFamily = look.fontFamily),
        onClick = { offset -> annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { runCatching { uri.openUri(it.item) } } },
    )
}

private fun markdown(text: String, colors: ChatColors): AnnotatedString = buildAnnotatedString {
    Markdown.parse(text).forEach { run ->
        val start = length
        append(run.text)
        addStyle(
            SpanStyle(
                fontWeight = if (run.bold) FontWeight.SemiBold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = if (run.code) colors.codeBackground else Color.Unspecified,
                fontSize = if (run.code) 0.9.em else TextUnit.Unspecified,
                color = if (run.link != null) colors.link else Color.Unspecified,
                textDecoration = if (run.link != null) TextDecoration.Underline else null,
            ),
            start, length,
        )
        run.link?.let { addStringAnnotation("url", it, start, length) }
    }
}

@Composable
private fun MediaView(media: Media, linkColor: Color) {
    val uri = LocalUriHandler.current
    val look = LocalChatLook.current
    if (media.type == "image" || media.type == "sticker") {
        AsyncImage(
            model = media.url,
            contentDescription = media.caption,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(media.url) } },
        )
    } else {
        Text(
            media.caption ?: "Open attachment",
            Modifier.padding(top = 8.dp).clickable { runCatching { uri.openUri(media.url) } },
            color = look.colors.link, textDecoration = TextDecoration.Underline, fontSize = look.small, fontFamily = look.fontFamily,
        )
    }
}

/** A file on a message: an image shown inline, anything else as its name, opened on tap. */
@Composable
private fun AttachmentView(attachment: com.universalchatbot.chat.core.Attachment, color: Color) {
    val look = LocalChatLook.current
    val uri = LocalUriHandler.current
    val open = Modifier.clickable(enabled = attachment.url.startsWith("http")) { runCatching { uri.openUri(attachment.url) } }
    if (attachment.kind == "image" && attachment.url.isNotEmpty()) {
        AsyncImage(
            model = attachment.url,
            contentDescription = attachment.filename ?: "Attachment",
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(open),
        )
    } else {
        Row(
            Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, look.colors.border, RoundedCornerShape(4.dp))
                .then(open)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TablerIconView(TablerIcon.File, color, 16.dp)
            Text(attachment.filename ?: "attachment", color = color, fontSize = look.tiny, maxLines = 1, fontFamily = look.fontFamily)
        }
    }
}

@Composable
private fun Typing() {
    val look = LocalChatLook.current
    val c = look.colors
    val pulse = rememberInfiniteTransition(label = "typing")
    val alpha by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse")
    val shape = RoundedCornerShape(look.radius)
    Row(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .shadow(1.5.dp, shape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(shape)
                .background(c.botBackground)
                .border(1.dp, c.border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TablerIconView(TablerIcon.MessageDots, c.botText, 20.dp, Modifier.alpha(alpha))
            Text("Typing...", color = c.botText, fontSize = look.small, fontFamily = look.fontFamily)
        }
    }
}

/** A reply chip: page-coloured with a border, filled with the visitor colour while pressed. */
@Composable
private fun Chip(text: String, radius: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false, fontSize: TextUnit? = null) {
    val look = LocalChatLook.current
    val c = look.colors
    val shape = RoundedCornerShape(radius)
    Text(
        text,
        modifier
            .clip(shape)
            .background(c.background)
            .border(1.dp, c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (muted) c.surfaceText else c.botText,
        fontSize = fontSize ?: look.small,
        fontWeight = FontWeight.Medium,
        fontFamily = look.fontFamily,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Entrypoints(state: ChatState, controller: ChatController) {
    val look = LocalChatLook.current
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.entrypoints.forEach { entry -> Chip(entry.label, look.radius, { controller.start(entry) }, fontSize = 14.sp) }
        Chip("Something else", look.radius, controller::startWithoutEntrypoint, fontSize = 14.sp)
    }
}

@Composable
private fun InputBar(state: ChatState, controller: ChatController, pickFile: (onPicked: (Upload) -> Unit) -> Unit) {
    val look = LocalChatLook.current
    val c = look.colors
    var text by rememberSaveable { mutableStateOf("") }
    var staged by remember { mutableStateOf<Upload?>(null) }
    val canSend = (text.isNotBlank() || staged != null) && !state.typing
    val send = {
        if (canSend) {
            controller.send(text, staged)
            text = ""
            staged = null
        }
    }
    val shape = RoundedCornerShape(look.radius)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .drawBehind { drawLine(c.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(12.dp),
    ) {
    staged?.let { file -> StagedFile(file) { staged = null } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.border, shape)
                .clickable { pickFile { staged = it } }
                .padding(12.dp)
                .semantics { contentDescription = "Attach a file" },
        ) { TablerIconView(TablerIcon.Paperclip, c.surfaceText, 20.dp) }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(color = c.surfaceText, fontSize = look.small, fontFamily = look.fontFamily),
            cursorBrush = SolidColor(c.surfaceText),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("Type your message...", color = c.surfaceText.copy(alpha = 0.7f), fontSize = look.small, fontFamily = look.fontFamily)
                    inner()
                }
            },
        )
        VisitorButton("Send", TablerIcon.ArrowRight, enabled = canSend, iconAfter = true, onClick = send)
    }
    }
}

/** The picked file, above the input until it is sent or removed. */
@Composable
private fun StagedFile(file: Upload, onRemove: () -> Unit) {
    val look = LocalChatLook.current
    val c = look.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (file.kind == "image" && file.previewUri != null) {
            AsyncImage(model = file.previewUri, contentDescription = "Attachment preview", modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            TablerIconView(TablerIcon.File, c.surfaceText, 20.dp)
        }
        Text(file.filename, Modifier.widthIn(max = 160.dp), color = c.surfaceText, fontSize = look.tiny, maxLines = 1, fontFamily = look.fontFamily)
        Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(2.dp).semantics { contentDescription = "Remove attachment" }) {
            TablerIconView(TablerIcon.X, c.surfaceText, 16.dp)
        }
    }
}

/** A picked document as an upload: its name, type and size from the provider, its bytes read when sending. */
internal fun uploadFrom(context: android.content.Context, uri: android.net.Uri): Upload {
    val resolver = context.contentResolver
    var name = "attachment"
    var size = 0L
    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return Upload(name, mime, size, uri.toString()) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            resolver.openInputStream(uri)?.use(Uploads::readCapped) ?: ByteArray(0)
        }
    }
}

/** The send and start-again buttons: visitor-coloured, the deployment's radius, an icon beside the label. */
@Composable
private fun VisitorButton(label: String, icon: TablerIcon, enabled: Boolean, iconAfter: Boolean, horizontal: Dp = 16.dp, onClick: () -> Unit) {
    val look = LocalChatLook.current
    val c = look.colors
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(look.radius))
            .background(c.userBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!iconAfter) TablerIconView(icon, c.userText, 20.dp)
        Text(label, color = c.userText, fontSize = look.base, fontWeight = FontWeight.SemiBold, fontFamily = look.fontFamily)
        if (iconAfter) TablerIconView(icon, c.userText, 16.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(state: ChatState, controller: ChatController) {
    val look = LocalChatLook.current
    val c = look.colors
    var expanded by remember(state.choices) { mutableStateOf(false) }
    val visible = if (expanded) state.choices else state.choices.take(5)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .drawBehind { drawLine(c.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quick replies:", Modifier.padding(start = 4.dp, top = 8.dp), color = c.surfaceText, fontSize = look.tiny, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { choice -> Chip(choice.label, look.radius * 0.75f, { controller.choose(choice) }) }
            if (state.choices.size > 5) {
                Chip(if (expanded) "Show less" else "+${state.choices.size - 5} more", look.radius * 0.75f, { expanded = !expanded }, muted = true)
            }
        }
    }
}

@Composable
private fun ClosingPanel(state: ChatState, controller: ChatController) {
    val look = LocalChatLook.current
    val c = look.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .drawBehind { drawLine(c.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.botText.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            TablerIconView(TablerIcon.Message, c.botText, 24.dp)
        }
        state.csat?.let { CsatPanel(it, controller::rate) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Thanks for chatting", Modifier.padding(bottom = 4.dp), color = c.botText, fontSize = look.small, fontWeight = FontWeight.SemiBold, fontFamily = look.fontFamily)
            Text(
                "We're here whenever you need us. Start a new chat anytime.",
                color = c.surfaceText, fontSize = look.tiny, textAlign = TextAlign.Center, fontFamily = look.fontFamily,
            )
        }
        VisitorButton("Start a new chat", TablerIcon.Message, enabled = true, iconAfter = false, horizontal = 24.dp, onClick = controller::restart)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CsatPanel(prompt: CsatPrompt, onRate: (Int) -> Unit) {
    val look = LocalChatLook.current
    val c = look.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (prompt) {
            is CsatPrompt.Asking -> {
                Text(prompt.question, color = c.botText, fontSize = look.small, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontFamily = look.fontFamily)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    (1..5).forEach { score ->
                        val shape = RoundedCornerShape(look.radius * 0.75f)
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                                .clip(shape)
                                .background(c.background)
                                .border(1.dp, c.border, shape)
                                .clickable { onRate(score) }
                                .semantics { contentDescription = "$score out of 5" }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("$score", color = c.botText, fontSize = look.small, fontWeight = FontWeight.SemiBold, fontFamily = look.fontFamily) }
                    }
                }
            }
            is CsatPrompt.Thanked -> Text(prompt.thankYou, color = c.botText, fontSize = look.small, fontWeight = FontWeight.SemiBold, fontFamily = look.fontFamily)
        }
    }
}

/** The details form, one question at a time as the website widget asks them. */
@Composable
private fun UserInfoSheet(state: ChatState, controller: ChatController) {
    val look = LocalChatLook.current
    val c = look.colors
    val fields = state.config.userInfoFields
    var step by remember { mutableIntStateOf(0) }
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    val field = fields.getOrElse(step) { "name" }
    val required = field in state.config.userInfoRequiredFields
    val value = values.value[field].orEmpty()
    val last = step >= fields.lastIndex
    val name = values.value["name"].orEmpty()

    val (title, subtitle, placeholder) = when (field) {
        "name" -> Triple(if (step == 0) "Hey there 👋" else "Great!", "What should we call you?", "Your name")
        "email" -> Triple(if (name.isNotBlank()) "Great to meet you, $name" else "Great!", "What's your email?", "your@email.com")
        else -> Triple("Last thing...", "Your phone number?", "+1 (555) 000-0000")
    }
    val advance = {
        if (last) controller.submitUserInfo(values.value) else step++
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).padding(16.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(look.radius)
        Column(
            Modifier
                .widthIn(max = 448.dp)
                .fillMaxWidth()
                .shadow(24.dp, shape)
                .clip(shape)
                .background(c.surface)
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                Box(Modifier.fillMaxWidth((step + 1f) / fields.size.coerceAtLeast(1)).height(2.dp).background(c.primary.copy(alpha = 0.8f)))
            }
            Column(Modifier.padding(horizontal = 40.dp, vertical = 48.dp)) {
                Text(title, color = c.botText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em, lineHeight = 28.sp, fontFamily = look.fontFamily)
                Text(subtitle, Modifier.padding(top = 8.dp, bottom = 40.dp).alpha(0.8f), color = c.surfaceText, fontSize = 16.sp, fontFamily = look.fontFamily)
                BasicTextField(
                    value = value,
                    onValueChange = { values.value = values.value + (field to it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(c.primary, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
                        .padding(vertical = 12.dp),
                    textStyle = TextStyle(color = c.botText, fontSize = 20.sp, fontFamily = look.fontFamily),
                    cursorBrush = SolidColor(c.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field) {
                            "email" -> KeyboardType.Email
                            "phone" -> KeyboardType.Phone
                            else -> KeyboardType.Text
                        },
                        capitalization = if (field == "name") KeyboardCapitalization.Words else KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { if (!required || value.isNotBlank()) advance() }),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) Text(placeholder, color = c.surfaceText.copy(alpha = 0.5f), fontSize = 20.sp, fontFamily = look.fontFamily)
                            inner()
                        }
                    },
                )
                Row(Modifier.padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!required) {
                        Text(
                            "Skip",
                            Modifier.alpha(0.6f).clickable { if (last) controller.submitUserInfo(values.value) else step++ }.padding(horizontal = 20.dp, vertical = 10.dp),
                            color = c.surfaceText, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily,
                        )
                    }
                    val enabled = !required || value.isNotBlank()
                    Row(
                        Modifier
                            .weight(1f)
                            .alpha(if (enabled) 1f else 0.5f)
                            .clip(RoundedCornerShape(look.radius * 0.5f))
                            .background(c.primary)
                            .clickable(enabled = enabled) { advance() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (last) "Start chatting" else "Continue", color = Color.White, fontWeight = FontWeight.Medium, fontFamily = look.fontFamily)
                        Spacer(Modifier.width(8.dp))
                        TablerIconView(TablerIcon.ArrowRight, Color.White, 16.dp)
                    }
                }
                Row(Modifier.padding(top = 40.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    fields.indices.forEach { index ->
                        Box(
                            Modifier
                                .height(4.dp)
                                .width(if (index == step) 24.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (index <= step) c.primary else c.border.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}
