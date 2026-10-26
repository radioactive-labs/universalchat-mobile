package com.universalchatbot.chat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Something the bot offers to answer with. The label is what gets sent back. */
data class Choice(val key: String, val label: String)

/** A way into the flow, offered before a conversation exists. */
data class Entrypoint(val id: String, val label: String)

data class Media(val url: String, val type: String, val caption: String?)

/**
 * A file on a message: one the visitor sent, or one already stored. [url] is
 * the signed address to fetch it from, or a local content address while the
 * visitor's own copy is still uploading. [kind] is image, video, audio or
 * document.
 */
data class Attachment(val url: String, val kind: String, val filename: String?, val mimeType: String?)

/**
 * A file the visitor picked to send. [read] gives its bytes when the upload
 * starts. [previewUri] is shown in their bubble meanwhile.
 */
data class Upload(
    val filename: String,
    val mimeType: String,
    val size: Long,
    val previewUri: String?,
    val read: suspend () -> ByteArray,
) {
    val kind get() = Uploads.kindOf(mimeType)
}

/** What the server will take, kept in step with the website widget's file picker. */
object Uploads {
    const val MAX_MB = 25
    const val MAX_BYTES = MAX_MB * 1024L * 1024L

    val ACCEPTED_TYPES = listOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "application/pdf",
        "audio/ogg", "audio/mpeg", "audio/mp4", "audio/amr",
        "video/mp4", "video/3gpp",
        "text/plain",
    )

    fun kindOf(mimeType: String?) = when {
        mimeType == null -> "document"
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("audio/") -> "audio"
        else -> "document"
    }

    const val TOO_LARGE = "That file is too large. The limit is $MAX_MB MB."

    /** Why the server would refuse it, worded for the visitor, or null when it will take it. */
    fun refusal(upload: Upload): String? = when {
        upload.size > MAX_BYTES -> TOO_LARGE
        upload.mimeType !in ACCEPTED_TYPES -> "That type of file can't be sent here."
        else -> null
    }

    /**
     * Reads a picked file, stopping once it passes the limit. A provider that
     * does not report a size gets past [refusal], and this keeps a huge file
     * from being read into memory whole.
     */
    fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw ApiException(0, null, TOO_LARGE)
        }
    }
}

/** A stored upload, named by the reference the next turn claims it with. */
data class MediaRef(val ref: String, val mimeType: String, val mediaType: String)

enum class Author { USER, BOT, AGENT }

enum class Delivery { SENDING, SENT, FAILED }

data class ChatMessage(
    val id: String,
    val author: Author,
    val text: String,
    val media: Media? = null,
    val authorName: String? = null,
    /** ISO 8601, as the server sends it. Null while a message is still sending. */
    val sentAt: String? = null,
    val delivery: Delivery = Delivery.SENT,
    val error: String? = null,
    /** The server's id, for paging back from this message. Null for live and local messages. */
    val historyId: Long? = null,
    /**
     * The id an agent reply is sent live under, which its history copy
     * carries too, so the two can be told to be the same message. Null for
     * the visitor's own messages and the bot's.
     */
    val platformId: String? = null,
    /** The files on the message, in the order they were sent. */
    val attachments: List<Attachment> = emptyList(),
    /** The file a failed send was carrying, so retrying sends it again. */
    val pendingUpload: Upload? = null,
)

data class CsatSettings(val enabled: Boolean, val question: String?, val thankYou: String?)

/** The deployment's chat settings: the widget customizer's values, the ways in, and CSAT. */
data class ChatConfig(
    val style: WidgetStyle,
    val greeting: String?,
    val collectUserInfo: String?,
    val userInfoFields: List<String>,
    val userInfoRequiredFields: List<String>,
    val entrypoints: List<Entrypoint>,
    val csat: CsatSettings,
) {
    companion object {
        val EMPTY = ChatConfig(WidgetStyle.DEFAULT, null, null, emptyList(), emptyList(), emptyList(), CsatSettings(false, null, null))

        fun parse(body: JsonObject): ChatConfig {
            val widget = body["chatWidget"].asObject()
            return ChatConfig(
                style = WidgetStyle.parse(widget, body.string("fontStylesheet")),
                greeting = widget.string("greeting"),
                collectUserInfo = widget.string("collectUserInfo")?.takeUnless { it == "never" },
                userInfoFields = widget.string("userInfoFields").csv().ifEmpty { listOf("name", "email") },
                userInfoRequiredFields = widget.string("userInfoRequiredFields").csv(),
                entrypoints = body["entrypoints"].asArray().mapNotNull {
                    val entry = it.asObject()
                    val id = entry.string("id")
                    val label = entry.string("label")
                    if (id != null && label != null) Entrypoint(id, label) else null
                },
                csat = body["csat"].asObject().let {
                    CsatSettings(it["enabled"]?.jsonPrimitive?.booleanOrNull == true, it.string("question"), it.string("thankYou"))
                },
            )
        }
    }
}

/** One turn's answer from the processor. */
sealed interface TurnResult {
    data class Reply(val terminal: Boolean, val message: String?, val choices: List<Choice>, val media: Media?) : TurnResult

    /** A person has taken over; their reply arrives on the cable. */
    data object Skipped : TurnResult
}

data class History(
    val conversationId: Long?,
    val status: String?,
    val messages: List<ChatMessage>,
    val lastChoices: List<Choice>,
    val hasOlder: Boolean,
    val cableToken: String?,
    val csatOffered: Boolean,
    val signedIn: Boolean = false,
    /** A signed-in visitor has an earlier conversation to look back on. */
    val pastConversations: Boolean = false,
)

/** One of a signed-in visitor's conversations, as their list shows it. */
data class ConversationSummary(
    val id: Long,
    val ended: Boolean,
    val startedAt: String?,
    val lastAuthor: Author,
    val lastAuthorName: String?,
    val lastText: String,
    val lastHasAttachment: Boolean,
    val lastSentAt: String?,
)

data class ConversationPage(val conversations: List<ConversationSummary>, val hasMore: Boolean)

internal fun parseConversationSummary(element: JsonElement): ConversationSummary? {
    val entry = element.asObject()
    val id = entry.long("id") ?: return null
    val last = entry["last_message"].asObject()
    return ConversationSummary(
        id = id,
        ended = entry.bool("ended"),
        startedAt = entry.string("started_at"),
        lastAuthor = when (last.string("role")) {
            "user" -> Author.USER
            "agent" -> Author.AGENT
            else -> Author.BOT
        },
        lastAuthorName = last.string("author_name"),
        lastText = last.string("text") ?: "",
        lastHasAttachment = last.bool("has_attachment"),
        lastSentAt = last.string("sent_at"),
    )
}

/** An event on the conversation's cable stream. */
sealed interface CableEvent {
    data class AgentMessage(val message: ChatMessage) : CableEvent
    data class ConversationClosed(val conversationId: Long?, val csatOffered: Boolean) : CableEvent
}

internal fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun String?.csv(): List<String> = this?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/**
 * Choices arrive as an object of key to label, where a label can itself be an
 * object carrying `value` or `label`, or as a list of `{key, value}`.
 */
internal fun parseChoices(element: JsonElement?): List<Choice> = when (element) {
    is JsonObject -> element.entries.mapNotNull { (key, value) -> choiceLabel(value)?.let { Choice(key, it) } }
    is JsonArray -> element.mapNotNull {
        val entry = it.asObject()
        val label = choiceLabel(entry["value"] ?: entry["label"])
        label?.let { l -> Choice(entry.string("key") ?: l, l) }
    }
    else -> emptyList()
}

private fun choiceLabel(value: JsonElement?): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> value.string("value") ?: value.string("label")
    else -> null
}

/** Media arrives as `{url, type, caption}`, or as a bare URL. */
internal fun parseMedia(element: JsonElement?): Media? = when (element) {
    is JsonObject -> element.string("url")?.let { Media(it, element.string("type") ?: "image", element.string("caption")) }
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { Media(it, "image", null) }
    JsonNull, null -> null
    else -> null
}

internal fun parseHistoryMessage(element: JsonElement): ChatMessage? {
    val entry = element.asObject()
    val author = when (entry.string("role")) {
        "user" -> Author.USER
        "agent" -> Author.AGENT
        "bot" -> Author.BOT
        else -> return null
    }
    val media = parseMedia(entry["media"])
    val text = entry.string("text") ?: ""
    val attachments = parseAttachments(entry["attachments"])
    if (text.isEmpty() && media == null && attachments.isEmpty()) return null
    val id = entry.long("id")
    return ChatMessage(
        id = "history-${id ?: entry.string("sent_at")}",
        author = author,
        text = text,
        media = media,
        authorName = entry.string("author_name"),
        sentAt = entry.string("sent_at"),
        historyId = id,
        platformId = entry.string("platform_message_id"),
        attachments = attachments,
    )
}

/**
 * The files on a message, live or from history: `{url, filename, kind,
 * content_type}` each. One with no url never arrived and is left out.
 */
internal fun parseAttachments(element: JsonElement?): List<Attachment> = element.asArray().mapNotNull {
    val file = it.asObject()
    file.string("url")?.let { url -> Attachment(url, file.string("kind") ?: Uploads.kindOf(file.string("content_type")), file.string("filename"), file.string("content_type")) }
}

internal fun JsonObject.choicesOf(key: String) = parseChoices(this[key])

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.arrayOrEmpty(): JsonArray = runCatching { jsonArray }.getOrDefault(JsonArray(emptyList()))
