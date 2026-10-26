package com.universalchatbot.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ChatControllerTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val identity = InMemoryIdentityStore()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path!!.substringBefore("?")
                return routes["${request.method} $path"]?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        route("GET", "config") { json("""{"chatWidget":{"title":"Acme"},"entrypoints":[],"csat":{"enabled":true,"question":"Rate us"}}""") }
        route("POST", "visitor") { json("""{"visitor":"anon-token"}""") }
        route("GET", "history") { json("""{"messages":[],"has_older":false}""") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun route(method: String, path: String, response: () -> MockResponse) = routeBy(method, path) { response() }

    /** A route that answers by what was asked. */
    private fun routeBy(method: String, path: String, response: (RecordedRequest) -> MockResponse) {
        routes["$method /deployments/7/$path"] = response
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun controller(): ChatController {
        val api = ApiClient(server.url("/").toString(), "7", OkHttpClient())
        return ChatController(api, identity, scope) { _, _ -> null }
    }

    private fun ChatController.awaitState(predicate: (ChatState) -> Boolean): ChatState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun bodies(path: String) = requests.filter { it.path!!.startsWith("/deployments/7/$path") }
        .map { Json.parseToJsonElement(it.body.clone().readUtf8()).jsonObject }

    @Test
    fun `a reply showing on the open screen is reported read, and nothing once the screen closes`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":3,"status":"active","messages":[{"id":2,"role":"agent","text":"Your card is on its way","sent_at":"2026-10-11T10:00:01Z"}],"has_older":false,"cable_token":"t"}""")
        }
        route("POST", "read") { MockResponse().setResponseCode(204) }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 1 }
        runBlocking { withTimeout(5_000) { while (bodies("read").isEmpty()) delay(20) } }

        val report = bodies("read").single()
        assertEquals("held", report["visitor"]!!.jsonPrimitive.content)
        assertEquals("3", report["conversation_id"]!!.jsonPrimitive.content)

        chat.close()
        val reported = bodies("read").size
        runBlocking { delay(200) }
        assertEquals(reported, bodies("read").size)
    }

    @Test
    fun `a new visitor gets an anonymous identity and the flow starts`() {
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi! Pick one","choices":{"a":"Orders","b":"Billing"}}""") }

        val chat = controller()
        chat.open()
        val state = chat.awaitState { it.messages.isNotEmpty() }

        assertEquals("anon-token", identity.anonymousVisitor)
        assertEquals("Hi! Pick one", state.messages.single().text)
        assertEquals(listOf("Orders", "Billing"), state.choices.map { it.label })
        val turn = bodies("processor").single()
        assertEquals("", turn["input"]!!.jsonPrimitive.content)
        assertEquals("anon-token", turn["visitor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an open conversation is resumed with its last choices and nothing is sent`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"user","text":"hello","sent_at":"2026-10-11T10:00:00Z"},{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"},"sent_at":"2026-10-11T10:00:01Z"}],"has_older":true,"cable_token":"t"}""")
        }

        val chat = controller()
        chat.open()
        val state = chat.awaitState { !it.loading && it.messages.size == 2 }

        assertEquals(listOf(Author.USER, Author.BOT), state.messages.map { it.author })
        assertEquals(listOf("Yes"), state.choices.map { it.label })
        assertTrue(state.hasOlder)
        assertTrue(bodies("processor").isEmpty())
        assertTrue(requests.none { it.path!!.contains("/visitor") })
    }

    @Test
    fun `a choice is answered with its label, and a terminal reply ends the conversation and asks for a rating`() {
        var turns = 0
        route("POST", "processor") {
            turns++
            if (turns == 1) json("""{"type":"prompt","message":"Pick","choices":{"a":"Orders"}}""") else json("""{"type":"terminal","message":"Bye"}""")
        }
        val chat = controller()
        chat.open()
        val first = chat.awaitState { it.choices.isNotEmpty() }

        route("GET", "history") { json("""{"conversation_id":3,"status":"completed","messages":[{"id":1,"role":"bot","text":"Bye"}],"csat_offered":true}""") }
        chat.choose(first.choices.single())
        val state = chat.awaitState { it.csat != null }

        assertEquals("Orders", bodies("processor").last()["input"]!!.jsonPrimitive.content)
        assertTrue(state.ended)
        assertEquals(CsatPrompt.Asking("Rate us"), state.csat)
        assertEquals(listOf("Orders", "Bye"), state.messages.takeLast(2).map { it.text })
    }

    @Test
    fun `a paused bot answers nothing and the sent message stays sent`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"agent","text":"Hi, Sam here","author_name":"Sam"}],"cable_token":"t"}""") }
        route("POST", "processor") { json("""{"type":"skip"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 1 }
        chat.send("my order is late")
        val state = chat.awaitState { !it.typing && it.messages.size == 2 && it.messages.last().delivery == Delivery.SENT }

        assertEquals("Sam", state.messages.first().authorName)
        assertEquals(Author.USER, state.messages.last().author)
    }

    @Test
    fun `a failed send is marked failed with the server's words, and can be retried`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Ask away"}],"cable_token":"t"}""") }
        route("POST", "processor") { MockResponse().setResponseCode(429).setBody("""{"error":"You're sending messages too fast."}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 1 }
        chat.send("hello")
        val failed = chat.awaitState { it.messages.lastOrNull()?.delivery == Delivery.FAILED }
        assertEquals("You're sending messages too fast.", failed.messages.last().error)

        route("POST", "processor") { json("""{"type":"prompt","message":"Got it"}""") }
        chat.retry(failed.messages.last())
        val state = chat.awaitState { it.messages.lastOrNull()?.text == "Got it" }
        assertEquals(listOf("Ask away", "hello", "Got it"), state.messages.map { it.text })
        assertEquals(Delivery.SENT, state.messages[1].delivery)
    }

    @Test
    fun `a flow with ways in offers them instead of starting, and one starts it`() {
        route("GET", "config") { json("""{"chatWidget":{},"entrypoints":[{"id":"billing","label":"Billing question"}]}""") }
        route("POST", "processor") { json("""{"type":"prompt","message":"About billing"}""") }

        val chat = controller()
        chat.open()
        val offered = chat.awaitState { it.entrypoints.isNotEmpty() }
        assertTrue(bodies("processor").isEmpty())

        chat.start(offered.entrypoints.single())
        val state = chat.awaitState { it.messages.size == 2 }
        assertTrue(state.entrypoints.isEmpty())
        assertEquals("Billing question", bodies("processor").single()["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `details asked before the chat go with the first turn, but never for a signed-in user`() {
        route("GET", "config") { json("""{"chatWidget":{"collectUserInfo":"before_chat"}}""") }
        route("POST", "processor") { json("""{"type":"prompt","message":"Hello Ada"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.askForUserInfo }
        chat.submitUserInfo(mapOf("name" to "Ada", "email" to "ada@example.com", "phone" to ""))
        chat.awaitState { it.messages.isNotEmpty() }

        val info = bodies("processor").single()["user_info"]!!.jsonObject
        assertEquals("Ada", info["name"]!!.jsonPrimitive.content)
        assertNull(info["phone"])

        val signedIn = InMemoryIdentityStore().apply { signedInVisitor = "user-token"; signedInUserId = "42" }
        val api = ApiClient(server.url("/").toString(), "7", OkHttpClient())
        val userChat = ChatController(api, signedIn, scope) { _, _ -> null }
        userChat.open()
        val state = userChat.awaitState { it.messages.isNotEmpty() }
        assertFalse(state.askForUserInfo)
        assertEquals("user-token", bodies("processor").last()["visitor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `attributes the app set go with every turn`() {
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi"}""") }

        val chat = controller()
        chat.attributes = mapOf("plan" to "pro", "seats" to 3, "beta" to true, "gone" to null)
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }

        assertEquals(
            Json.parseToJsonElement("""{"plan":"pro","seats":3,"beta":true,"gone":null}"""),
            bodies("processor").single()["attributes"],
        )
    }

    private fun upload(name: String = "receipt.png", mime: String = "image/png", size: Long = 3) =
        Upload(name, mime, size, "content://picked/1") { byteArrayOf(1, 2, 3) }

    @Test
    fun `a file is uploaded first and the turn claims it, shown in the visitor's bubble`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Send a photo"}],"cable_token":"t"}""") }
        route("POST", "media") { json("""{"ref":"signed-ref","mime_type":"image/png","media_type":"image"}""") }
        route("POST", "processor") { json("""{"type":"prompt","message":"Got it"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 1 }
        chat.send("Here", upload())
        val state = chat.awaitState { it.messages.lastOrNull()?.text == "Got it" }

        val media = requests.single { it.path!!.startsWith("/deployments/7/media") }
        val form = media.body.clone().readUtf8()
        assertTrue(form.contains("name=\"visitor\""))
        assertTrue(form.contains("held"))
        assertTrue(form.contains("filename=\"receipt.png\""))
        val turn = bodies("processor").last()
        assertEquals("signed-ref", turn["media_url"]!!.jsonPrimitive.content)
        assertEquals("image", turn["media_type"]!!.jsonPrimitive.content)
        assertEquals("Here", turn["input"]!!.jsonPrimitive.content)
        val sent = state.messages[1]
        assertEquals(listOf(Attachment("content://picked/1", "image", "receipt.png", "image/png")), sent.attachments)
        assertEquals(Delivery.SENT, sent.delivery)
        assertNull(sent.pendingUpload)
    }

    @Test
    fun `a file the server would refuse is never uploaded`() {
        identity.anonymousVisitor = "held"
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi"}""") }
        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }

        chat.send("", upload(size = Uploads.MAX_BYTES + 1))
        assertEquals("That file is too large. The limit is 25 MB.", chat.awaitState { it.error != null }.error)
        chat.dismissError()
        chat.send("", upload(name = "a.zip", mime = "application/zip"))
        assertEquals("That type of file can't be sent here.", chat.awaitState { it.error != null }.error)
        assertTrue(requests.none { it.path!!.contains("/media") })
    }

    @Test
    fun `a file whose provider gave no size is refused once reading passes the limit`() {
        identity.anonymousVisitor = "held"
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi"}""") }
        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }
        val endless = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        }

        chat.send("", Upload("huge.mp4", "video/mp4", 0, "content://picked/2") { Uploads.readCapped(endless) })

        val failed = chat.awaitState { s -> s.messages.any { it.delivery == Delivery.FAILED } }.messages.last()
        assertEquals(Uploads.TOO_LARGE, failed.error)
        assertTrue(requests.none { it.path!!.contains("/media") })
    }

    @Test
    fun `a refused token gets a fresh visitor and the upload goes through, and a failed one can be retried with its file`() {
        identity.anonymousVisitor = "lapsed"
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Send it"}],"cable_token":"t"}""") }
        var uploads = 0
        route("POST", "media") {
            uploads++
            when (uploads) {
                1 -> MockResponse().setResponseCode(401).setBody("""{"error":"visitor is required"}""")
                2 -> MockResponse().setResponseCode(422).setBody("""{"error":"File is not an accepted type"}""")
                else -> json("""{"ref":"r","mime_type":"application/pdf","media_type":"document"}""")
            }
        }
        route("POST", "processor") { json("""{"type":"prompt","message":"Thanks"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 1 }
        chat.send("", upload(name = "doc.pdf", mime = "application/pdf"))
        val failed = chat.awaitState { it.messages.lastOrNull()?.delivery == Delivery.FAILED }
        assertEquals("anon-token", identity.anonymousVisitor)
        assertEquals("File is not an accepted type", failed.messages.last().error)

        chat.retry(failed.messages.last())
        val state = chat.awaitState { it.messages.lastOrNull()?.text == "Thanks" }
        assertEquals("doc.pdf", state.messages[1].attachments.single().filename)
        assertEquals("r", bodies("processor").last()["media_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `history shows every file a message carried`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"agent","text":"","attachments":[{"url":"https://acme/files/x","filename":"receipt.png","kind":"image","content_type":"image/png"},{"url":"https://acme/files/y","filename":"terms.pdf","content_type":"application/pdf"},{"url":null,"filename":"lost.pdf","kind":"document"}]}],"cable_token":"t"}""")
        }

        val chat = controller()
        chat.open()
        val state = chat.awaitState { it.messages.size == 1 }

        assertEquals(
            listOf(
                Attachment("https://acme/files/x", "image", "receipt.png", "image/png"),
                Attachment("https://acme/files/y", "document", "terms.pdf", "application/pdf"),
            ),
            state.messages.single().attachments,
        )
    }

    @Test
    fun `a refused signed-in visitor falls back to the replacement the server hands over`() {
        identity.signedInVisitor = "lapsed"
        identity.signedInUserId = "42"
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi"}""").setHeader("X-Webchat-Visitor", "fresh-anon") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }

        assertNull(identity.signedInVisitor)
        assertEquals("fresh-anon", identity.anonymousVisitor)
    }

    @Test
    fun `a deployment that is not answering shows as unavailable`() {
        route("GET", "config") { MockResponse().setResponseCode(404) }

        val chat = controller()
        chat.open()

        assertTrue(chat.awaitState { !it.loading }.unavailable)
    }

    @Test
    fun `cable events add agent replies once, record reads and close the conversation`() {
        assertEquals(
            CableEvent.AgentMessage(ChatMessage("m1", Author.AGENT, "On it", authorName = "Sam", sentAt = "2026-10-11T10:00:00Z", platformId = "m1")),
            CableClient.parseEvent(Json.parseToJsonElement("""{"event":"agent_message","data":{"platform_message_id":"m1","role":"agent","body":"On it","author_name":"Sam","sent_at":"2026-10-11T10:00:00Z"}}""").jsonObject),
        )
        assertEquals(
            CableEvent.ConversationClosed(3, true),
            CableClient.parseEvent(Json.parseToJsonElement("""{"event":"conversation_closed","data":{"conversation_id":3,"csat_offered":true}}""").jsonObject),
        )
        assertEquals(1_000L, CableClient.backoff(0))
        assertEquals(30_000L, CableClient.backoff(10))
    }

    @Test
    fun `an agent reply on the cable carries every file it was sent with`() {
        val event = CableClient.parseEvent(Json.parseToJsonElement(
            """{"event":"agent_message","data":{"platform_message_id":"m2","role":"agent","body":null,"author_name":"Sam","sent_at":"2026-10-11T10:00:00Z","attachments":[{"url":"https://acme/files/a","filename":"a.png","kind":"image","content_type":"image/png"},{"url":"https://acme/files/b","filename":"b.pdf","kind":"document","content_type":"application/pdf"}]}}"""
        ).jsonObject) as CableEvent.AgentMessage

        assertEquals("", event.message.text)
        assertEquals(
            listOf(
                Attachment("https://acme/files/a", "image", "a.png", "image/png"),
                Attachment("https://acme/files/b", "document", "b.pdf", "application/pdf"),
            ),
            event.message.attachments,
        )
    }

    @Test
    fun `an agent reply with neither text nor a file is not shown`() {
        assertNull(CableClient.parseEvent(Json.parseToJsonElement(
            """{"event":"agent_message","data":{"platform_message_id":"m3","body":"","attachments":[]}}"""
        ).jsonObject))
    }

    @Test
    fun `reopening the screen shows replies that arrived while it was closed, once each`() {
        identity.anonymousVisitor = "held"
        val first = """{"id":1,"role":"user","text":"hello","sent_at":"2026-10-11T10:00:00Z"},{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"},"sent_at":"2026-10-11T10:00:01Z"}"""
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[$first],"has_older":false,"cable_token":"t"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.size == 2 }
        chat.close()

        route("GET", "history") {
            json("""{"conversation_id":3,"status":"active","messages":[$first,{"id":3,"role":"agent","text":"Here you go","author_name":"Sam","sent_at":"2026-10-11T10:05:00Z","attachments":[{"url":"https://acme/files/a","filename":"a.pdf","kind":"document"},{"url":"https://acme/files/b","filename":"b.pdf","kind":"document"}]}],"has_older":false,"cable_token":"t"}""")
        }
        chat.open()
        val state = chat.awaitState { it.messages.size == 3 }

        assertEquals(listOf("hello", "Pick", "Here you go"), state.messages.map { it.text })
        assertEquals(listOf("a.pdf", "b.pdf"), state.messages.last().attachments.map { it.filename })
        assertEquals(listOf("Yes"), state.choices.map { it.label })
        assertFalse(state.ended)
    }

    @Test
    fun `reopening after the conversation ended meanwhile closes it and asks for a rating`() {
        identity.anonymousVisitor = "held"
        val first = """{"id":2,"role":"bot","text":"Pick","choices":{"x":"Yes"},"sent_at":"2026-10-11T10:00:01Z"}"""
        route("GET", "history") { json("""{"conversation_id":3,"status":"active","messages":[$first],"has_older":false,"cable_token":"t"}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.choices.isNotEmpty() }
        chat.close()

        route("GET", "history") {
            json("""{"conversation_id":3,"status":"completed","messages":[$first,{"id":3,"role":"agent","text":"All sorted","sent_at":"2026-10-11T10:05:00Z"}],"has_older":false,"csat_offered":true}""")
        }
        chat.open()
        val state = chat.awaitState { it.ended }

        assertEquals(listOf("Pick", "All sorted"), state.messages.map { it.text })
        assertTrue(state.choices.isEmpty())
        assertEquals(CsatPrompt.Asking("Rate us"), state.csat)
    }

    @Test
    fun `reopening replaces what this session showed with the server's copy instead of repeating it`() {
        route("POST", "processor") { json("""{"type":"prompt","message":"Hi! Pick one","choices":{"a":"Orders"}}""") }

        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }
        chat.close()

        route("GET", "history") {
            json("""{"conversation_id":5,"status":"active","messages":[{"id":1,"role":"bot","text":"Hi! Pick one","choices":{"a":"Orders"},"sent_at":"2026-10-11T10:00:00Z"},{"id":2,"role":"agent","text":"Sam here","sent_at":"2026-10-11T10:01:00Z"}],"has_older":false,"cable_token":"t"}""")
        }
        chat.open()
        val state = chat.awaitState { it.messages.any { m -> m.text == "Sam here" } }

        assertEquals(listOf("Hi! Pick one", "Sam here"), state.messages.map { it.text })
    }

    @Test
    fun `a notification about the conversation on screen picks up the reply it was about`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }
        val chat = controller()
        chat.open()
        chat.awaitState { !it.loading && it.messages.isNotEmpty() }

        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"},{"id":2,"role":"agent","text":"Hello!","sent_at":"2026-10-11T10:02:00Z"}],"has_older":false}""")
        }
        chat.openConversation(9)
        val state = chat.awaitState { it.messages.size == 2 }

        assertEquals("Hello!", state.messages.last().text)
        assertNull(state.past)
    }

    @Test
    fun `merging history keeps earlier pages in front and unsent messages after`() {
        val older = ChatMessage("history-1", Author.BOT, "older", historyId = 1)
        val stale = ChatMessage("history-5", Author.AGENT, "on page", historyId = 5)
        val live = ChatMessage("m1", Author.AGENT, "live copy", platformId = "m1")
        val failed = ChatMessage("local-1", Author.USER, "retry me", delivery = Delivery.FAILED)
        val page = listOf(ChatMessage("history-5", Author.AGENT, "on page", historyId = 5), ChatMessage("history-6", Author.AGENT, "live copy", historyId = 6, platformId = "m1"))

        assertEquals(listOf(older) + page + failed, ChatController.mergeHistory(listOf(older, stale, live, failed), page, latestHasOlder = true))
        assertEquals(page + failed, ChatController.mergeHistory(listOf(older, stale, live, failed), page, latestHasOlder = false))
    }

    @Test
    fun `merging history keeps a live reply the page was read too early to have, once, before unsent messages`() {
        val sent = ChatMessage("local-1", Author.USER, "hello")
        val earlier = ChatMessage("m1", Author.AGENT, "first reply", platformId = "m1")
        val late = ChatMessage("m2", Author.AGENT, "arrived after the page", platformId = "m2")
        val sending = ChatMessage("local-2", Author.USER, "still going", delivery = Delivery.SENDING)
        val page = listOf(
            ChatMessage("history-1", Author.USER, "hello", historyId = 1),
            ChatMessage("history-2", Author.AGENT, "first reply", historyId = 2, platformId = "m1"),
        )

        assertEquals(page + late + sending, ChatController.mergeHistory(listOf(sent, earlier, late, sending), page, latestHasOlder = false))
    }

    @Test
    fun `merging history keeps a live reply shown before the page in front of it`() {
        val live = ChatMessage("m9", Author.AGENT, "from before", platformId = "m9")
        val onPage = ChatMessage("history-5", Author.AGENT, "on page", historyId = 5)

        assertEquals(listOf(live, onPage), ChatController.mergeHistory(listOf(live, onPage), listOf(onPage), latestHasOlder = true))
    }

    @Test
    fun `history reads each message's live id`() {
        val message = parseHistoryMessage(Json.parseToJsonElement("""{"id":4,"role":"agent","text":"Sam here","platform_message_id":"m4","sent_at":"2026-10-11T10:00:00Z"}"""))!!

        assertEquals("m4", message.platformId)
        assertEquals(4L, message.historyId)
    }

    @Test
    fun `reopening on a different conversation shows it in place of the one on screen`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":3,"status":"active","messages":[{"id":1,"role":"bot","text":"Old chat","choices":{"x":"Yes"},"sent_at":"2026-10-11T10:00:00Z"}],"has_older":true,"cable_token":"t"}""")
        }
        val chat = controller()
        chat.open()
        chat.awaitState { it.messages.isNotEmpty() }
        chat.close()

        route("GET", "history") {
            json("""{"conversation_id":4,"status":"active","messages":[{"id":7,"role":"bot","text":"New chat","choices":{"y":"Go"},"sent_at":"2026-10-11T11:00:00Z"}],"has_older":false,"cable_token":"t2"}""")
        }
        chat.open()
        val state = chat.awaitState { it.messages.any { m -> m.text == "New chat" } }

        assertEquals(listOf("New chat"), state.messages.map { it.text })
        assertEquals(listOf("Go"), state.choices.map { it.label })
        assertFalse(state.hasOlder)
    }

    @Test
    fun `reopening after a conversation with nothing on screen ended meanwhile closes it and asks for a rating`() {
        route("POST", "processor") { json("""{"type":"prompt","message":"","choices":{}}""") }
        val chat = controller()
        chat.open()
        chat.awaitState { !it.loading && !it.typing && bodies("processor").isNotEmpty() }
        chat.close()

        route("GET", "history") { json("""{"conversation_id":6,"status":"completed","messages":[],"has_older":false,"csat_offered":true}""") }
        chat.open()
        val state = chat.awaitState { it.ended }

        assertTrue(state.messages.isEmpty())
        assertEquals(CsatPrompt.Asking("Rate us"), state.csat)
    }

    @Test
    fun `a signed-in visitor opens an earlier conversation from their list, read-only`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hello again","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }
        route("GET", "conversations") {
            json("""{"conversations":[{"id":9,"ended":false,"started_at":"2026-10-11T10:00:00Z","last_message":{"role":"user","text":"hello again","sent_at":"2026-10-11T10:00:00Z"}},{"id":4,"ended":true,"started_at":"2026-09-01T10:00:00Z","last_message":{"role":"bot","text":"Refund sent","sent_at":"2026-09-01T10:05:00Z"}}],"has_more":false}""")
        }

        val chat = controller()
        chat.open()
        assertTrue(chat.awaitState { !it.loading && it.messages.isNotEmpty() }.pastConversations)

        chat.showPastConversations()
        val list = chat.awaitState { it.past?.loading == false }.past!!
        assertEquals(listOf(9L, 4L), list.conversations.map { it.id })
        assertEquals("Refund sent", list.conversations.last().lastText)

        route("GET", "history") {
            json("""{"conversation_id":4,"status":"completed","messages":[{"id":20,"role":"user","text":"refund please","sent_at":"2026-09-01T10:00:00Z"}],"has_older":false}""")
        }
        chat.openPastConversation(list.conversations.last())
        val open = chat.awaitState { it.past?.open != null && it.past?.transcriptLoading == false }
        assertEquals(listOf("refund please"), open.past!!.transcript.map { it.text })
        assertEquals("hello again", open.messages.single().text)
        assertTrue(requests.last().path!!.contains("conversation_id=4"))

        chat.hidePastConversations()
        assertNull(chat.state.value.past)
    }

    @Test
    fun `picking the conversation still running just closes the list`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }
        route("GET", "conversations") {
            json("""{"conversations":[{"id":9,"ended":false,"last_message":{"role":"user","text":"hi"}}],"has_more":false}""")
        }

        val chat = controller()
        chat.open()
        chat.awaitState { !it.loading && it.messages.isNotEmpty() }
        chat.showPastConversations()
        val current = chat.awaitState { it.past?.loading == false }.past!!.conversations.single()

        chat.openPastConversation(current)

        assertNull(chat.state.value.past)
    }

    @Test
    fun `an anonymous visitor is not offered past conversations`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","signed_in":false,"past_conversations":false,"messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }

        val chat = controller()
        chat.open()

        assertFalse(chat.awaitState { !it.loading && it.messages.isNotEmpty() }.pastConversations)
    }

    @Test
    fun `a signed-in visitor's conversation that ends becomes one to look back on`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") { json("""{"messages":[],"has_older":false,"signed_in":true,"past_conversations":false}""") }
        route("POST", "processor") { json("""{"type":"terminal","message":"Bye"}""") }

        val chat = controller()
        chat.open()

        assertTrue(chat.awaitState { it.ended }.pastConversations)
    }

    @Test
    fun `a notification about a conversation that has since ended opens it from past conversations`() {
        identity.anonymousVisitor = "held"
        routeBy("GET", "history") { request ->
            if (request.path!!.contains("conversation_id=4")) {
                json("""{"conversation_id":4,"status":"completed","messages":[{"id":20,"role":"agent","text":"Refunded","author_name":"Sam","sent_at":"2026-09-02T09:00:00Z"}],"has_older":false}""")
            } else {
                json("""{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hello again","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
            }
        }
        route("GET", "conversations") { json("""{"conversations":[],"has_more":false}""") }

        val chat = controller()
        chat.openConversation(4)
        chat.open()
        val state = chat.awaitState { it.past?.open?.startedAt != null }

        assertEquals(4L, state.past!!.open!!.id)
        assertEquals(listOf("Refunded"), state.past!!.transcript.map { it.text })
        assertEquals("hello again", state.messages.single().text)
    }

    @Test
    fun `a notification about the conversation on screen just opens the chat`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","signed_in":true,"past_conversations":true,"messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }

        val chat = controller()
        chat.openConversation(9)
        chat.open()
        chat.awaitState { !it.loading && it.messages.isNotEmpty() }

        assertNull(chat.state.value.past)
    }

    @Test
    fun `an anonymous visitor's notification opens the chat, since they have no past conversations`() {
        identity.anonymousVisitor = "held"
        route("GET", "history") {
            json("""{"conversation_id":9,"status":"active","signed_in":false,"messages":[{"id":1,"role":"user","text":"hi","sent_at":"2026-10-11T10:00:00Z"}],"has_older":false}""")
        }

        val chat = controller()
        chat.open()
        chat.awaitState { !it.loading && it.messages.isNotEmpty() }
        chat.openConversation(4)
        Thread.sleep(200)

        assertNull(chat.state.value.past)
        assertTrue(requests.none { it.path!!.contains("conversation_id=4") })
    }
}
