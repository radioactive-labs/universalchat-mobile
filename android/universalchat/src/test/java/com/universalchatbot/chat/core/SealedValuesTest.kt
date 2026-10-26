package com.universalchatbot.chat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SealedValuesTest {
    private val stored = mutableMapOf<String, String>()
    private val values = object : Values {
        override fun get(key: String) = stored[key]
        override fun put(key: String, value: String?) {
            if (value == null) stored.remove(key) else stored[key] = value
        }
    }
    private var sealing = true
    private val cipher = object : SecretCipher {
        override fun seal(plain: String) = if (sealing) "sealed:" + plain.reversed() else null
        override fun open(sealed: String) = sealed.removePrefix("sealed:").takeIf { it != sealed }?.reversed()
    }
    private val sealed = SealedValues(values, cipher)

    @Test
    fun `a value is kept sealed, never in plain`() {
        sealed.put("visitor", "v-1")

        assertEquals(mapOf("visitor_sealed" to "sealed:1-v"), stored)
        assertEquals("v-1", sealed.get("visitor"))
    }

    @Test
    fun `a value an earlier version kept in plain is sealed the first time it is read`() {
        stored["visitor"] = "v-old"

        assertEquals("v-old", sealed.get("visitor"))
        assertEquals(mapOf("visitor_sealed" to "sealed:dlo-v"), stored)
    }

    @Test
    fun `a sealed value that no longer opens reads as gone`() {
        stored["visitor_sealed"] = "from another device"

        assertNull(sealed.get("visitor"))
        assertNull(stored["visitor_sealed"])
    }

    @Test
    fun `a value stays in plain when sealing fails, so the visitor is kept`() {
        sealing = false

        sealed.put("visitor", "v-2")

        assertEquals("v-2", sealed.get("visitor"))
    }

    @Test
    fun `clearing removes both forms`() {
        stored["visitor"] = "v-old"
        sealed.put("visitor", null)

        assertEquals(emptyMap<String, String>(), stored)
    }
}
