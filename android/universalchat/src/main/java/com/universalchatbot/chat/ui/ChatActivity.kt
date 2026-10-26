package com.universalchatbot.chat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.universalchatbot.chat.core.Uploads
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalchatbot.chat.UniversalChat

/** The full-screen chat. Opened with [UniversalChat.present]. */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UniversalChat.isConfigured) {
            finish()
            return
        }
        enableEdgeToEdge()
        val controller = UniversalChat.controller
        openConversationFrom(intent)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            var themeOverride by remember { mutableStateOf(UniversalChat.themeOverride) }
            val style = state.config.style
            val font = rememberWebFont(style.fontFamily, style.fontStylesheet)
            var onPicked by remember { mutableStateOf<((com.universalchatbot.chat.core.Upload) -> Unit)?>(null) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) onPicked?.invoke(uploadFrom(this, uri))
            }
            // The app may take a link for its own navigation; when it does,
            // the chat closes so the visitor lands where the link points.
            val system = LocalUriHandler.current
            val links = remember(system) { AppLinks(system, ::finish) }
            CompositionLocalProvider(LocalUriHandler provides links) {
                ChatTheme(style, resolveDark(style, themeOverride), font) {
                    ChatScreen(
                        state, controller,
                        themeOverride = themeOverride,
                        // The website widget's order: dark, then light, then back to the deployment's own.
                        onToggleTheme = {
                            themeOverride = when (themeOverride) {
                                null -> "dark"
                                "dark" -> "light"
                                else -> null
                            }
                            UniversalChat.themeOverride = themeOverride
                        },
                        onClose = ::finish,
                        pickFile = { callback ->
                            onPicked = callback
                            picker.launch(Uploads.ACCEPTED_TYPES.toTypedArray())
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openConversationFrom(intent)
    }

    private fun openConversationFrom(intent: Intent?) {
        intent?.getLongExtra(EXTRA_CONVERSATION, -1L)?.takeIf { it > 0 }?.let(UniversalChat.controller::openConversation)
    }

    override fun onStart() {
        super.onStart()
        UniversalChat.chatVisible = true
        UniversalChat.controller.open()
    }

    override fun onStop() {
        UniversalChat.chatVisible = false
        UniversalChat.controller.close()
        super.onStop()
    }

    companion object {
        private const val EXTRA_CONVERSATION = "com.universalchatbot.chat.CONVERSATION"

        /** Opens the chat; with [conversationId], on that conversation, reusing a chat already open. */
        fun intent(context: Context, conversationId: Long? = null) = Intent(context, ChatActivity::class.java).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            conversationId?.let {
                putExtra(EXTRA_CONVERSATION, it)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

/** Offers each link to the app's [UniversalChat.linkHandler] first, closing the chat when the app takes it. */
internal class AppLinks(private val system: UriHandler, private val close: () -> Unit) : UriHandler {
    override fun openUri(uri: String) {
        if (UniversalChat.linkHandler?.invoke(uri) == true) close() else system.openUri(uri)
    }
}
