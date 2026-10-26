package com.universalchatbot.chat.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request the server answered with an error, worded for the visitor where the server words it. */
class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

data class TurnResponse(val result: TurnResult, val replacementVisitor: String?)

/** The deployment's public endpoints. See sdks/README.md for the protocol. */
class ApiClient(
    serverUrl: String,
    private val deploymentId: String,
    private val http: OkHttpClient,
) {
    private val base: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    val serverOrigin: String = base.newBuilder().encodedPath("/").build().toString().trimEnd('/')

    /** Null when the deployment is not answering. */
    suspend fun config(): ChatConfig? {
        val response = execute(Request.Builder().url(url("config")).get().build())
        return response.use {
            if (it.code == 404) return null
            ChatConfig.parse(it.checked().jsonBody())
        }
    }

    suspend fun anonymousVisitor(): String = post("visitor", buildJsonObject {}).let { (body, _) ->
        body.string("visitor") ?: throw ApiException(200, null, "No visitor in response")
    }

    /** The signed-in user's visitor, exchanged for the token the customer's server signed. */
    suspend fun signedInVisitor(userToken: String): Pair<String, String> =
        post("visitor", buildJsonObject { put("user_token", userToken) }).let { (body, _) ->
            val visitor = body.string("visitor") ?: throw ApiException(200, null, "No visitor in response")
            visitor to (body.string("user_id") ?: "")
        }

    suspend fun turn(
        visitor: String?,
        input: String,
        userInfo: Map<String, String>? = null,
        attributes: Map<String, Any?> = emptyMap(),
        media: MediaRef? = null,
    ): TurnResponse {
        val payload = buildJsonObject {
            put("input", input)
            visitor?.let { put("visitor", it) }
            // The file this turn sends, claimed by the reference its upload got.
            media?.let {
                put("media_url", it.ref)
                put("media_type", it.mediaType)
                put("mime_type", it.mimeType)
            }
            if (attributes.isNotEmpty()) put("attributes", JsonObject(attributes.mapValues { (_, value) -> attributeValue(value) }))
            if (!userInfo.isNullOrEmpty()) {
                put("user_info", JsonObject(userInfo.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            }
        }
        val (body, response) = post("processor", payload)
        val result = when (body.string("type")) {
            "skip", null -> TurnResult.Skipped
            else -> TurnResult.Reply(
                terminal = body.string("type") == "terminal",
                message = body.string("message"),
                choices = body.choicesOf("choices"),
                media = parseMedia(body["media"]),
            )
        }
        return TurnResponse(result, response.header(REPLACEMENT_VISITOR_HEADER))
    }

    /** The visitor's latest conversation, or with [conversationId] one of their earlier ones. */
    suspend fun history(visitor: String, beforeId: Long? = null, conversationId: Long? = null): History {
        val url = url("history").newBuilder().addQueryParameter("visitor", visitor).apply {
            beforeId?.let { addQueryParameter("before_id", it.toString()) }
            conversationId?.let { addQueryParameter("conversation_id", it.toString()) }
        }.build()
        val body = execute(Request.Builder().url(url).get().build()).use { it.checked().jsonBody() }
        val raw = body["messages"].asArray()
        val messages = raw.mapNotNull(::parseHistoryMessage)
        val last = raw.lastOrNull()?.asObject()
        return History(
            conversationId = body.long("conversation_id"),
            status = body.string("status"),
            messages = messages,
            lastChoices = if (last != null && last.string("role") != "user") last.choicesOf("choices") else emptyList(),
            hasOlder = body.bool("has_older"),
            cableToken = body.string("cable_token"),
            csatOffered = body.bool("csat_offered"),
            signedIn = body.bool("signed_in"),
            pastConversations = body.bool("past_conversations"),
        )
    }

    /** A signed-in visitor's conversations, newest first. */
    suspend fun conversations(visitor: String, beforeId: Long? = null): ConversationPage {
        val url = url("conversations").newBuilder().addQueryParameter("visitor", visitor).apply {
            beforeId?.let { addQueryParameter("before_id", it.toString()) }
        }.build()
        val body = execute(Request.Builder().url(url).get().build()).use { it.checked().jsonBody() }
        return ConversationPage(body["conversations"].asArray().mapNotNull(::parseConversationSummary), body.bool("has_more"))
    }

    /** The thank-you to show. */
    suspend fun rate(visitor: String, conversationId: Long?, score: Int): String? =
        post("csat", buildJsonObject {
            put("visitor", visitor)
            put("score", score)
            conversationId?.let { put("conversation_id", it) }
        }).first.string("thank_you")

    /** The visitor has the conversation in view: our replies up to now are read. */
    suspend fun markRead(visitor: String, conversationId: Long?) {
        post("read", buildJsonObject {
            put("visitor", visitor)
            conversationId?.let { put("conversation_id", it) }
        })
    }

    /** Stores a file the visitor is about to send, bound to them. */
    suspend fun uploadMedia(visitor: String, filename: String, mimeType: String, bytes: ByteArray): MediaRef {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("visitor", visitor)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val response = execute(Request.Builder().url(url("media")).post(body).build())
        return response.use {
            val json = it.checked().jsonBody()
            MediaRef(
                ref = json.string("ref") ?: throw ApiException(200, null, "No reference in response"),
                mimeType = json.string("mime_type") ?: mimeType,
                mediaType = json.string("media_type") ?: Uploads.kindOf(mimeType),
            )
        }
    }

    suspend fun registerDevice(visitor: String, token: String) {
        post("devices", buildJsonObject {
            put("visitor", visitor)
            put("token", token)
            put("os", "android")
        })
    }

    suspend fun unregisterDevice(visitor: String, token: String) {
        val payload = buildJsonObject {
            put("visitor", visitor)
            put("token", token)
        }
        execute(Request.Builder().url(url("devices")).delete(payload.toString().toRequestBody(JSON)).build()).use { it.checked() }
    }

    private suspend fun post(path: String, payload: JsonObject): Pair<JsonObject, Response> {
        val response = execute(Request.Builder().url(url(path)).post(payload.toString().toRequestBody(JSON)).build())
        return response.use { (it.checked().jsonBody()) to it }
    }

    private fun attributeValue(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments("deployments/$deploymentId/$path").build()

    private fun Response.jsonBody(): JsonObject {
        val text = body?.string().orEmpty()
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text).asObject() }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun Response.checked(): Response {
        if (isSuccessful) return this
        val body = runCatching { json.parseToJsonElement(peekBody(64 * 1024).string()).asObject() }.getOrNull()
        val error = body?.string("error")
        throw ApiException(code, error, error ?: "Request failed ($code)")
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            })
        }
    }

    companion object {
        const val REPLACEMENT_VISITOR_HEADER = "X-Webchat-Visitor"
        private val JSON = "application/json".toMediaType()
    }
}
