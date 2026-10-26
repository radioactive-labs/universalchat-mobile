package com.universalchatbot.chat.core

/** Key-value storage, as SharedPreferences offers it. A null value removes the key. */
interface Values {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Encrypts a value under a key kept off the stored data. Null when it can't. */
interface SecretCipher {
    fun seal(plain: String): String?
    fun open(sealed: String): String?
}

/**
 * Values kept sealed by [cipher], each beside where an earlier version kept
 * it in plain. A plain value moves to its sealed key the first time it is
 * read. A sealed value that no longer opens, as after a backup is restored
 * onto another device, reads as gone. Where sealing fails the value stays in
 * plain, so the visitor is never lost.
 */
class SealedValues(private val values: Values, private val cipher: SecretCipher) {
    fun get(key: String): String? {
        values.get(sealedKey(key))?.let { sealed ->
            return cipher.open(sealed) ?: run { values.put(sealedKey(key), null); null }
        }
        val plain = values.get(key) ?: return null
        put(key, plain)
        return plain
    }

    fun put(key: String, value: String?) {
        val sealed = value?.let(cipher::seal)
        if (value != null && sealed == null) {
            values.put(key, value)
            values.put(sealedKey(key), null)
            return
        }
        values.put(sealedKey(key), sealed)
        values.put(key, null)
    }

    private fun sealedKey(key: String) = "${key}_sealed"
}
