package com.universalchatbot.chat.ui

import androidx.compose.ui.platform.UriHandler
import com.universalchatbot.chat.UniversalChat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    private val opened = mutableListOf<String>()
    private val system = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }
    private var closed = false
    private val links = AppLinks(system) { closed = true }

    @After
    fun tearDown() {
        UniversalChat.linkHandler = null
    }

    @Test
    fun `a link the app takes closes the chat and goes nowhere else`() {
        UniversalChat.linkHandler = { it.startsWith("myapp://") }

        links.openUri("myapp://orders/1001")

        assertTrue(closed)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a link the app leaves opens as usual and the chat stays`() {
        UniversalChat.linkHandler = { it.startsWith("myapp://") }

        links.openUri("https://example.com/help")

        assertFalse(closed)
        assertEquals(listOf("https://example.com/help"), opened)
    }

    @Test
    fun `without a handler every link opens as usual`() {
        links.openUri("myapp://orders/1001")

        assertFalse(closed)
        assertEquals(listOf("myapp://orders/1001"), opened)
    }
}
