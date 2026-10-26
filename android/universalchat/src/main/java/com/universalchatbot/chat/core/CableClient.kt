package com.universalchatbot.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min

/**
 * The conversation's ActionCable stream: agent replies, read receipts and the
 * conversation closing. Reconnects with backoff, and treats a server that has
 * stopped pinging as gone.
 *
 * A rejected subscription means the token lapsed; [onRejected] is asked for a
 * fresh one.
 */
class CableClient(
    serverOrigin: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val onEvent: (CableEvent) -> Unit,
    private val onRejected: () -> Unit,
) {
    private val origin = serverOrigin.trimEnd('/')
    private val url = origin.replaceFirst("http", "ws") + "/cable"
    private val json = Json { ignoreUnknownKeys = true }

    private var socket: WebSocket? = null
    private var token: String? = null
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var watchdog: Job? = null
    private var stopped = true

    val connected get() = socket != null && !stopped

    /** Subscribes with the token, replacing any earlier subscription. */
    @Synchronized
    fun subscribe(cableToken: String) {
        if (cableToken == token && socket != null && !stopped) return
        stop()
        token = cableToken
        stopped = false
        attempts = 0
        connect()
    }

    @Synchronized
    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        watchdog?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    @Synchronized
    private fun connect() {
        // Rails checks the Origin of a cable connection against the app's own
        // host; a browser sends it for free, a native socket has to say it.
        val request = Request.Builder().url(url).header("Origin", origin).build()
        socket = http.newWebSocket(request, Listener())
    }

    private fun identifier(): String = buildJsonObject {
        put("channel", CHANNEL)
        put("cable_token", token)
    }.toString()

    @Synchronized
    private fun scheduleReconnect() {
        if (stopped) return
        socket = null
        watchdog?.cancel()
        reconnectJob?.cancel()
        val wait = backoff(attempts++)
        reconnectJob = scope.launch {
            delay(wait)
            if (!stopped) connect()
        }
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(STALE_AFTER_MS)
            socket?.cancel()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val frame = runCatching { json.parseToJsonElement(text).asObject() }.getOrNull() ?: return
            when (frame.string("type")) {
                "welcome" -> {
                    attempts = 0
                    armWatchdog()
                    webSocket.send(buildJsonObject {
                        put("command", "subscribe")
                        put("identifier", identifier())
                    }.toString())
                }
                "ping" -> armWatchdog()
                "reject_subscription" -> {
                    stop()
                    onRejected()
                }
                "disconnect" -> if (frame.bool("reconnect")) webSocket.cancel()
                null -> frame["message"]?.let { parseEvent(it.asObject())?.let(onEvent) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === socket) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) scheduleReconnect()
        }
    }

    companion object {
        const val CHANNEL = "UniversalInbox::WebchatChannel"

        /** Rails pings every 3 seconds. */
        const val STALE_AFTER_MS = 10_000L

        fun backoff(attempt: Int): Long = min(30_000L, 1_000L shl min(attempt, 5))

        fun parseEvent(message: JsonObject): CableEvent? {
            val data = message["data"].asObject()
            return when (message.string("event")) {
                "agent_message" -> {
                    val id = data.string("platform_message_id") ?: return null
                    val text = data.string("body") ?: ""
                    val attachments = parseAttachments(data["attachments"])
                    if (text.isEmpty() && attachments.isEmpty()) return null
                    CableEvent.AgentMessage(
                        ChatMessage(
                            id = id,
                            author = Author.AGENT,
                            text = text,
                            authorName = data.string("author_name"),
                            sentAt = data.string("sent_at"),
                            platformId = id,
                            attachments = attachments,
                        )
                    )
                }
                "conversation_closed" -> CableEvent.ConversationClosed(data.long("conversation_id"), data.bool("csat_offered"))
                else -> null
            }
        }
    }
}

