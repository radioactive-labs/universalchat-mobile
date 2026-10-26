package com.universalchatbot.chat.core

import com.universalchatbot.chat.core.Markdown.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun `bold, italic, code and links become styled runs`() {
        assertEquals(
            listOf(
                Run("Your order "),
                Run("1001", bold = true),
                Run(" ships "),
                Run("today", italic = true),
                Run(". Track it "),
                Run("here", link = "https://acme.test/t"),
                Run(" or quote "),
                Run("ABC", code = true),
            ),
            Markdown.parse("Your order **1001** ships _today_. Track it [here](https://acme.test/t) or quote `ABC`"),
        )
    }

    @Test
    fun `bare links are linked without trailing punctuation, and list items become bullets`() {
        assertEquals(
            listOf(Run("See "), Run("https://acme.test/help", link = "https://acme.test/help"), Run(".\n• one\n• two")),
            Markdown.parse("See https://acme.test/help.\n- one\n* two"),
        )
    }

    @Test
    fun `snake_case and lone asterisks stay as written`() {
        assertEquals(listOf(Run("order_id is 5 * 3")), Markdown.parse("order_id is 5 * 3"))
    }
}
