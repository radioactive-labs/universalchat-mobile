package com.universalchatbot.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CableClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    /** A cable server that welcomes, records what the client sends, and answers the subscribe with [onSubscribe]. */
    private fun cableServer(onSubscribe: (WebSocket) -> Unit): LinkedBlockingQueue<String> {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"welcome"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                onSubscribe(webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        server.start()
        return received
    }

    @Test
    fun `it subscribes with the token, sends the server's origin, and delivers events`() {
        val events = LinkedBlockingQueue<CableEvent>()
        val identifier = """{\"channel\":\"UniversalInbox::WebchatChannel\",\"cable_token\":\"tok\"}"""
        val received = cableServer { socket ->
            socket.send("""{"type":"confirm_subscription","identifier":"$identifier"}""")
            socket.send("""{"identifier":"$identifier","message":{"event":"agent_message","data":{"platform_message_id":"m1","body":"Hello","author_name":"Sam"}}}""")
        }
        val origin = server.url("/").toString().trimEnd('/')

        val cable = CableClient(origin, OkHttpClient(), scope, { events += it }, {})
        cable.subscribe("tok")

        val subscribe = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(subscribe.contains("\"command\":\"subscribe\""))
        assertTrue(subscribe.contains("cable_token\\\":\\\"tok"))
        assertEquals(origin, server.takeRequest().getHeader("Origin"))

        val event = events.poll(5, TimeUnit.SECONDS) as CableEvent.AgentMessage
        assertEquals("Hello", event.message.text)
        cable.stop()
    }

    @Test
    fun `a rejected subscription asks for a fresh token`() {
        val rejected = CountDownLatch(1)
        cableServer { socket -> socket.send("""{"type":"reject_subscription"}""") }

        val cable = CableClient(server.url("/").toString(), OkHttpClient(), scope, {}, { rejected.countDown() })
        cable.subscribe("expired")

        assertTrue(rejected.await(5, TimeUnit.SECONDS))
        assertTrue(!cable.connected)
    }
}
