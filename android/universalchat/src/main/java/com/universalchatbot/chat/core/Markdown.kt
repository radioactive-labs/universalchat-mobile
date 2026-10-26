package com.universalchatbot.chat.core

/**
 * The Markdown a flow's messages use, reduced to runs of styled text: bold,
 * italic, inline code and links, with list items as bullets. Anything else is
 * shown as written.
 */
object Markdown {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    private val token = Regex(
        """\*\*(.+?)\*\*|__(.+?)__|\*(?!\s)(.+?)\*|(?<![\w])_(?!\s)(.+?)_(?![\w])|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s<>()]+[^\s<>().,;:!?'"])"""
    )

    fun parse(source: String): List<Run> {
        val text = source.lines().joinToString("\n") { line ->
            val bullet = Regex("""^(\s*)[-*+]\s+""").find(line)
            if (bullet != null) "${bullet.groupValues[1]}• ${line.substring(bullet.range.last + 1)}" else line
        }
        return inline(text, Run(""))
    }

    private fun inline(text: String, style: Run): List<Run> {
        val runs = mutableListOf<Run>()
        var at = 0
        for (match in token.findAll(text)) {
            if (match.range.first > at) runs += style.copy(text = text.substring(at, match.range.first))
            val g = match.groupValues
            runs += when {
                g[1].isNotEmpty() -> inline(g[1], style.copy(bold = true))
                g[2].isNotEmpty() -> inline(g[2], style.copy(bold = true))
                g[3].isNotEmpty() -> inline(g[3], style.copy(italic = true))
                g[4].isNotEmpty() -> inline(g[4], style.copy(italic = true))
                g[5].isNotEmpty() -> listOf(style.copy(text = g[5], code = true))
                g[6].isNotEmpty() -> inline(g[6], style.copy(link = g[7]))
                else -> listOf(style.copy(text = g[8], link = g[8]))
            }
            at = match.range.last + 1
        }
        if (at < text.length) runs += style.copy(text = text.substring(at))
        return runs
    }
}
