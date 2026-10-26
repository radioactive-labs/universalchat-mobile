package com.universalchatbot.chat.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.universalchatbot.chat.core.ApiClient
import com.universalchatbot.chat.core.Author
import com.universalchatbot.chat.core.ChatConfig
import com.universalchatbot.chat.core.ChatController
import com.universalchatbot.chat.core.ChatMessage
import com.universalchatbot.chat.core.ChatState
import com.universalchatbot.chat.core.Choice
import com.universalchatbot.chat.core.ConversationSummary
import com.universalchatbot.chat.core.PastConversations
import com.universalchatbot.chat.core.CsatPrompt
import com.universalchatbot.chat.core.CsatSettings
import com.universalchatbot.chat.core.Delivery
import com.universalchatbot.chat.core.Entrypoint
import com.universalchatbot.chat.core.InMemoryIdentityStore
import com.universalchatbot.chat.core.WidgetStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/**
 * The chat screen in the states a visitor meets, drawn from widget settings
 * the way the website widget draws them. Recorded with
 * `./gradlew recordPaparazziDebug`.
 */
class ChatScreenSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private val controller = ChatController(
        ApiClient("https://acme.example.com", "1", OkHttpClient()), InMemoryIdentityStore(), CoroutineScope(Dispatchers.Unconfined),
    ) { _, _ -> null }

    /** The widget customizer's Professional Indigo preset, as a deployment would save it. */
    private val indigo = WidgetStyle.parse(
        Json.parseToJsonElement(
            """
            {"title":"Acme Support","subtitle":"We usually reply in a few minutes",
             "lightColors":{"primaryColor":"#4f46e5","accentColor":"#6366f1","surfaceTextColor":"#475569","headerColor":"#4f46e5",
               "botMessageTextColor":"#1e293b","userMessageBackgroundColor":"#4f46e5","userMessageTextSecondaryColor":"#c7d2fe"},
             "darkColors":{"primaryColor":"#312e81","accentColor":"#6366f1","headerColor":"#312e81","botMessageTextSecondaryColor":"#a5b4fc",
               "userMessageBackgroundColor":"#3730a3","userMessageTextColor":"#ffffff","userMessageTextSecondaryColor":"#c7d2fe"}}
            """
        ).jsonObject,
        null,
    )

    private val defaults = WidgetStyle.parse(Json.parseToJsonElement("""{"title":"Acme Support","subtitle":"We usually reply in a few minutes"}""").jsonObject, null)

    private fun config(style: WidgetStyle) = ChatConfig.EMPTY.copy(
        style = style,
        greeting = "Hi! How can we help today?",
        csat = CsatSettings(true, "How did we do?", "Thanks for your feedback!"),
    )

    private fun bot(text: String, at: String = "2026-10-11T10:00:00Z") = ChatMessage("b${text.hashCode()}", Author.BOT, text, sentAt = at, historyId = 1)
    private fun user(text: String, at: String = "2026-10-11T10:00:05Z") = ChatMessage("u${text.hashCode()}", Author.USER, text, sentAt = at, historyId = 2)
    private fun agent(text: String) = ChatMessage("a${text.hashCode()}", Author.AGENT, text, authorName = "Sam", sentAt = "2026-10-11T10:02:00Z", historyId = 3)

    private fun snap(state: ChatState, dark: Boolean = false) = paparazzi.snapshot {
        ChatTheme(state.config.style, dark, null) {
            ChatScreen(state, controller, themeOverride = null, onToggleTheme = {}, onClose = {})
        }
    }

    private val asking = listOf(
        bot("Hi Ada! I can help with **orders**, billing or your account."),
        user("Where is my order?"),
        bot("Happy to check. Which order is it about?", "2026-10-11T10:00:06Z"),
    )
    private val orders = listOf(Choice("1001", "Order #1001"), Choice("1002", "Order #1002"), Choice("other", "Something else"))

    @Test
    fun signedInWithPastConversations() = snap(ChatState(config = config(indigo), loading = false, messages = asking, pastConversations = true))

    private val pastList = listOf(
        ConversationSummary(9, false, "2026-10-11T10:00:00Z", Author.BOT, null, "Happy to check. Which order is it about?", false, "2026-10-11T10:00:06Z"),
        ConversationSummary(4, true, "2026-09-02T09:00:00Z", Author.AGENT, "Sam", "I've refunded it. It will show within 3 days.", false, "2026-09-02T09:20:00Z"),
        ConversationSummary(2, true, "2026-08-14T16:00:00Z", Author.USER, null, "", true, "2026-08-14T16:04:00Z"),
    )

    @Test
    fun pastConversationsList() = snap(
        ChatState(config = config(indigo), loading = false, messages = asking, pastConversations = true, past = PastConversations(pastList, hasMore = true, loading = false))
    )

    @Test
    fun aPastConversationOpened() = snap(
        ChatState(
            config = config(indigo), loading = false, messages = asking, pastConversations = true,
            past = PastConversations(
                pastList, loading = false, open = pastList[1],
                transcript = listOf(
                    user("I was charged twice", "2026-09-02T09:00:00Z"),
                    agent("Hi, Sam here. I've refunded it. It will show within 3 days."),
                ),
            ),
        )
    )

    @Test
    fun defaultLook() = snap(ChatState(config = config(defaults), loading = false, messages = asking, choices = orders))

    @Test
    fun botAskingWithChoices() = snap(ChatState(config = config(indigo), loading = false, messages = asking, choices = orders, startedHere = true))

    @Test
    fun waysIn() = snap(
        ChatState(
            config = config(indigo), loading = false,
            entrypoints = listOf(Entrypoint("orders", "Track an order"), Entrypoint("billing", "Billing question"), Entrypoint("human", "Talk to a person")),
        )
    )

    @Test
    fun handedOffToAPerson() = snap(
        ChatState(
            config = config(indigo), loading = false,
            messages = listOf(
                user("I was charged twice"),
                bot("Sorry about that. Connecting you to a person.", "2026-10-11T10:00:06Z"),
                agent("Hi, Sam here. I can see the duplicate charge and I've refunded it. It will show within 3 days."),
                user("Thank you!", "2026-10-11T10:03:00Z"),
            ),
        )
    )

    @Test
    fun endedAskingForARating() = snap(
        ChatState(
            config = config(indigo), loading = false, ended = true,
            messages = listOf(user("That's all, thanks"), bot("Glad we could help. Have a great day!", "2026-10-11T10:00:06Z")),
            csat = CsatPrompt.Asking("How did we do?"),
        )
    )

    @Test
    fun aMessageThatFailedToSend() = snap(
        ChatState(
            config = config(indigo), loading = false,
            messages = listOf(bot("Ask me anything."), user("Can I change my delivery address?").copy(delivery = Delivery.FAILED, error = "Couldn't reach the chat service.")),
        )
    )

    @Test
    fun darkMode() = snap(
        ChatState(config = config(indigo), loading = false, typing = true, messages = listOf(bot("Hi! How can we help today?"), user("What are your opening hours?"))),
        dark = true,
    )

    @Test
    fun aFileTheVisitorSent() = snap(
        ChatState(
            config = config(indigo), loading = false,
            messages = listOf(
                bot("Please send your receipt and we'll look into it."),
                user("Here it is").copy(attachments = listOf(com.universalchatbot.chat.core.Attachment("https://acme.example.com/files/x", "document", "receipt-1001.pdf", "application/pdf"))),
                bot("Thanks, I can see the receipt.", "2026-10-11T10:00:09Z"),
            ),
        )
    )

    @Test
    fun askingForDetails() = snap(
        ChatState(config = config(indigo).copy(collectUserInfo = "before_chat", userInfoFields = listOf("name", "email")), loading = false, askForUserInfo = true)
    )
}
