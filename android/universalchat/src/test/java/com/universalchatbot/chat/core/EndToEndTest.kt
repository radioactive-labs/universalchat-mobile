package com.universalchatbot.chat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/**
 * Runs against a real server, only when told where one is:
 *
 *   UCB_E2E_SERVER       the server URL
 *   UCB_E2E_DEPLOYMENT   a mobile deployment whose flow asks a question with
 *                        choices, then hands off to a person
 *   UCB_E2E_JWT          a user token signed with that deployment's secret
 *   UCB_E2E_HANDOFF_FILE where to write the conversation id once handed off,
 *                        so whoever drives the test can reply as an agent
 */
class EndToEndTest {
    @Test
    fun `a signed-in user chats through to a person and gets their reply live`() {
        val server = System.getenv("UCB_E2E_SERVER")
        val deployment = System.getenv("UCB_E2E_DEPLOYMENT")
        val jwt = System.getenv("UCB_E2E_JWT")
        assumeNotNull(server, deployment, jwt)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient()
        val api = ApiClient(server, deployment, http)
        val identity = InMemoryIdentityStore()

        runBlocking {
            val (visitor, userId) = api.signedInVisitor(jwt)
            identity.signedInVisitor = visitor
            identity.signedInUserId = userId
            assertFalse(userId.isEmpty())

            val chat = ChatController(api, identity, scope) { onEvent, onRejected -> CableClient(api.serverOrigin, http, scope, onEvent, onRejected) }
            chat.open()
            val asked = withTimeout(15_000) { chat.state.first { it.choices.isNotEmpty() } }
            assertEquals(listOf("Orders", "Billing"), asked.choices.map { it.label })

            chat.choose(asked.choices.first())
            withTimeout(15_000) { chat.state.first { s -> s.messages.any { it.text.contains("Connecting you") } && !s.typing } }

            kotlinx.coroutines.delay(3_000)
            val conversation = api.history(visitor)
            System.getenv("UCB_E2E_HANDOFF_FILE")?.let { File(it).writeText(conversation.conversationId.toString()) }

            val replied = withTimeout(60_000) { chat.state.first { s -> s.messages.any { it.author == Author.AGENT } } }
            val reply = replied.messages.last { it.author == Author.AGENT }
            assertEquals("Hi, Sam here. Let me look at your order.", reply.text)
            assertEquals("Sam", reply.authorName)

            chat.send("Thanks Sam")
            withTimeout(15_000) { chat.state.first { it.messages.last().text == "Thanks Sam" && it.messages.last().delivery == Delivery.SENT } }
            chat.close()
        }
        scope.cancel()
    }
}
