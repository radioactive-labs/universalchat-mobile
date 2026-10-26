package com.universalchatbot.chat

import android.content.Context
import android.content.Intent
import com.universalchatbot.chat.core.ApiClient
import com.universalchatbot.chat.core.CableClient
import com.universalchatbot.chat.core.ChatController
import com.universalchatbot.chat.core.IdentityStore
import com.universalchatbot.chat.core.SharedPreferencesIdentityStore
import com.universalchatbot.chat.push.ChatNotifications
import com.universalchatbot.chat.ui.ChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The Universal Chatbot chat for one mobile deployment.
 *
 * ```
 * UniversalChat.configure(context, serverUrl = "https://acme.universalchatbot.com", deploymentId = "42")
 * UniversalChat.present(activity)
 * ```
 *
 * Sign a user in with a token your server signs ([login]); without one the
 * visitor is anonymous. Pass the FCM token to [setPushToken] and incoming
 * messages to [handlePush] to tell users when an agent replies.
 */
object UniversalChat {
    private lateinit var appContext: Context
    private lateinit var deploymentId: String
    private lateinit var identity: IdentityStore
    private lateinit var api: ApiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    internal lateinit var controller: ChatController
        private set

    /** Whether the chat screen is on screen, so a push for it is not shown twice. */
    @Volatile
    internal var chatVisible = false

    val isConfigured get() = ::controller.isInitialized

    /** The signed-in user's id, or null for an anonymous visitor. */
    val userId: String? get() = if (isConfigured) identity.signedInUserId else null

    /**
     * Call once, from Application.onCreate. [serverUrl] and [deploymentId] are
     * shown in the deployment's App setup panel.
     */
    @JvmStatic
    fun configure(context: Context, serverUrl: String, deploymentId: String) {
        appContext = context.applicationContext
        this.deploymentId = deploymentId
        identity = SharedPreferencesIdentityStore(appContext, deploymentId)
        api = ApiClient(serverUrl, deploymentId, http)
        controller = ChatController(api, identity, scope) { onEvent, onRejected ->
            CableClient(api.serverOrigin, http, scope, onEvent, onRejected)
        }
        ChatNotifications.createChannel(appContext)
    }

    /** Opens the chat screen. */
    @JvmStatic
    fun present(context: Context) {
        context.startActivity(ChatActivity.intent(context))
    }

    /**
     * Signs in the user [userToken] names: a JWT your server signs with the
     * deployment's identity secret. Their conversation follows them to any
     * device they sign in on, and agents see them as verified.
     *
     * Returns the user's id, or fails when the token does not verify.
     */
    suspend fun login(userToken: String): Result<String> = runCatching {
        val (visitor, userId) = api.signedInVisitor(userToken)
        val changedUser = identity.signedInUserId != userId
        identity.signedInVisitor = visitor
        identity.signedInUserId = userId
        moveDevice()
        if (changedUser) restartChat()
        userId
    }

    /** [login] for Java and callback-style callers. */
    @JvmStatic
    fun login(userToken: String, callback: (Result<String>) -> Unit) {
        scope.launch { callback(login(userToken)) }
    }

    /**
     * Signs the user out. Their device stops getting their notifications, and
     * the chat goes back to an anonymous conversation.
     */
    @JvmStatic
    fun logout() {
        if (identity.signedInVisitor == null) return
        identity.signedInVisitor = null
        identity.signedInUserId = null
        identity.userInfoCollected = false
        // Registering the token again moves it off the user, to the anonymous visitor.
        scope.launch { moveDevice() }
        restartChat()
    }

    /**
     * Custom attributes about the visitor, such as their plan or the screen
     * they opened the chat from. Flows read them as `{{@visitor.attributes.plan}}`
     * and agents see them beside the conversation. Merged with what was set
     * before; set a key to null to clear it.
     *
     * These come from the device, so a flow should not trust them for anything
     * that matters. Put those in the user token's `attributes` claim instead.
     */
    @JvmStatic
    fun setAttributes(attributes: Map<String, Any?>) {
        controller.attributes = controller.attributes + attributes
    }

    /** Pass the FCM registration token, from FirebaseMessagingService.onNewToken and at launch. */
    @JvmStatic
    fun setPushToken(token: String) {
        if (identity.pushToken == token && identity.pushVisitor == currentVisitor()) return
        identity.pushToken = token
        identity.pushVisitor = null
        scope.launch { moveDevice() }
    }

    /** Whether a push message's data is one of ours. */
    @JvmStatic
    fun isUniversalChatPush(data: Map<String, String>): Boolean =
        data["ucb"] == "1" && (!isConfigured || data["ucb_deployment_id"] == deploymentId)

    /**
     * Call from FirebaseMessagingService.onMessageReceived. Shows the reply as
     * a notification that opens the chat, unless the chat is already open.
     * Returns false for a message that is not ours.
     */
    @JvmStatic
    fun handlePush(context: Context, data: Map<String, String>, title: String?, body: String?): Boolean {
        if (!isUniversalChatPush(data)) return false
        if (!chatVisible) ChatNotifications.show(context, data, title, body)
        return true
    }

    /**
     * Opens the chat when [intent] came from tapping one of our notifications
     * that the system showed while the app was in the background. Call from
     * the launcher activity's onCreate and onNewIntent.
     */
    @JvmStatic
    fun handleNotificationTap(context: Context, intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        return handleNotificationTap(context, extras.keySet().associateWith { extras.get(it)?.toString().orEmpty() })
    }

    /**
     * Opens the chat on the conversation a tapped notification was about,
     * from its data. For push libraries that hand over the data rather than
     * the intent. Returns false for a notification that is not ours.
     */
    @JvmStatic
    fun handleNotificationTap(context: Context, data: Map<String, String>): Boolean {
        if (!isUniversalChatPush(data)) return false
        context.startActivity(ChatActivity.intent(context, data["ucb_conversation_id"]?.toLongOrNull()))
        return true
    }

    /**
     * Decides what happens to a link tapped in the chat, such as one a flow
     * sends to a screen of your app. Return true when your app handled it, and
     * the chat closes so the visitor lands there; false to open it as usual.
     */
    @JvmStatic
    var linkHandler: ((url: String) -> Boolean)? = null

    /** The visitor's light or dark choice from the header toggle, kept across launches. */
    internal var themeOverride: String?
        get() = identity.themeOverride
        set(value) {
            identity.themeOverride = value
        }

    private fun currentVisitor() = identity.signedInVisitor ?: identity.anonymousVisitor

    /** Registers the push token under whoever is chatting now. */
    private suspend fun moveDevice() {
        val token = identity.pushToken ?: return
        val visitor = currentVisitor() ?: runCatching { api.anonymousVisitor() }.getOrNull()?.also { identity.anonymousVisitor = it } ?: return
        if (identity.pushVisitor == visitor) return
        runCatching { api.registerDevice(visitor, token) }.onSuccess { identity.pushVisitor = visitor }
    }

    private fun restartChat() {
        controller.reset()
        if (chatVisible) controller.open()
    }
}
