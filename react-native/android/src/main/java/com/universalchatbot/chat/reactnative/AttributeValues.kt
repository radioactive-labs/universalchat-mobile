package com.universalchatbot.chat.reactnative

/**
 * JavaScript has one number type, so every number reaches the bridge as a
 * Double. A whole one goes on as a Long, so `{seats: 3}` is sent as 3 rather
 * than 3.0, the way the iOS bridge sends it. Maps and lists are walked.
 */
internal object AttributeValues {
    // 2^63, the first Double past Long.MAX_VALUE.
    private const val PAST_LONG = 9.223372036854775807E18

    fun normalize(value: Any?): Any? = when (value) {
        is Double -> if (value % 1.0 == 0.0 && value >= -PAST_LONG && value < PAST_LONG) value.toLong() else value
        is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to normalize(item) }
        is List<*> -> value.map(::normalize)
        else -> value
    }

    fun normalizeAll(attributes: Map<String, Any?>): Map<String, Any?> = attributes.mapValues { normalize(it.value) }
}
