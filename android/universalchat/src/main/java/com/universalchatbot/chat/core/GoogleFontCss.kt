package com.universalchatbot.chat.core

/**
 * Reads the font files out of a Google Fonts stylesheet. Asked for without a
 * browser's user agent, Google answers with TrueType files, one per weight,
 * which both platforms can load.
 */
object GoogleFontCss {
    private val face = Regex("""@font-face\s*\{([^}]*)\}""")
    private val weight = Regex("""font-weight:\s*(\d+)""")
    private val italic = Regex("""font-style:\s*italic""")
    private val src = Regex("""src:\s*url\(([^)]+)\)""")

    /** Weight to file URL, upright faces only. */
    fun files(css: String): Map<Int, String> = face.findAll(css).mapNotNull { match ->
        val block = match.groupValues[1]
        if (italic.containsMatchIn(block)) return@mapNotNull null
        val w = weight.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val url = src.find(block)?.groupValues?.get(1)?.trim('"', '\'') ?: return@mapNotNull null
        w to url
    }.toMap()
}
