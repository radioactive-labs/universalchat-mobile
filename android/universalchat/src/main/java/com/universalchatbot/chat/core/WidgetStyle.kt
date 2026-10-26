package com.universalchatbot.chat.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The widget customizer's settings, resolved the way the website widget
 * resolves them, so a deployment looks the same in an app as on the web: the
 * same colour keys styling the same parts, the same defaults, the same theme
 * choice, text size and corner radius.
 */
data class WidgetStyle(
    val theme: ThemeMode,
    val light: Palette,
    val dark: Palette,
    /** The base text size in px (dp/pt natively). Message text is 6/7 of it, labels 5/7. */
    val fontSize: Float,
    val borderRadius: Float,
    /** The first family the customizer named, or null for the system font. */
    val fontFamily: String?,
    /** Where Google serves that family, when it does. */
    val fontStylesheet: String?,
    val title: String,
    val subtitle: String,
) {
    val smallFontSize get() = fontSize * 0.857f
    val tinyFontSize get() = fontSize * 0.714f

    fun palette(dark: Boolean) = if (dark) this.dark else light

    companion object {
        const val DEFAULT_TITLE = "Chat with us"
        const val DEFAULT_SUBTITLE = "We'll reply as soon as we can"

        val DEFAULT = WidgetStyle(ThemeMode.LIGHT, Palette.LIGHT, Palette.DARK, 16f, 16f, null, null, DEFAULT_TITLE, DEFAULT_SUBTITLE)

        private val SYSTEM_FAMILIES = setOf(
            "ui-sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont", "segoe ui", "roboto",
            "helvetica neue", "helvetica", "arial", "sans-serif", "serif", "monospace", "inherit",
        )

        fun parse(widget: JsonObject, fontStylesheet: String?) = WidgetStyle(
            theme = ThemeMode.parse(widget.text("theme")),
            light = Palette.LIGHT.with(widget["lightColors"].asObject()),
            dark = Palette.DARK.with(widget["darkColors"].asObject()),
            fontSize = cssLength(widget.text("fontSize")) ?: 16f,
            borderRadius = cssLength(widget.text("borderRadius")) ?: 16f,
            fontFamily = firstFamily(widget.text("fontFamily")),
            fontStylesheet = fontStylesheet,
            title = widget.text("title") ?: DEFAULT_TITLE,
            subtitle = widget.text("subtitle") ?: DEFAULT_SUBTITLE,
        )

        /** `16px`, `1rem` or a bare number, in px. */
        fun cssLength(value: String?): Float? {
            val text = value?.trim()?.lowercase() ?: return null
            return when {
                text.endsWith("px") -> text.removeSuffix("px").trim().toFloatOrNull()
                text.endsWith("rem") -> text.removeSuffix("rem").trim().toFloatOrNull()?.times(16)
                text.endsWith("em") -> text.removeSuffix("em").trim().toFloatOrNull()?.times(16)
                else -> text.toFloatOrNull()
            }?.takeIf { it >= 0 }
        }

        /** The first family a CSS stack names, unless it is one of the system stacks. */
        fun firstFamily(stack: String?): String? {
            val first = stack?.split(",")?.firstOrNull()?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
            return first.takeUnless { it.lowercase() in SYSTEM_FAMILIES }
        }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM;

    companion object {
        // Following the page's own dark class has no meaning in an app, so it
        // follows the phone's setting.
        fun parse(value: String?) = when (value) {
            "dark" -> DARK
            "system", "tailwind_class" -> SYSTEM
            else -> LIGHT
        }
    }
}

/** One theme's colours, as ARGB, keyed as the widget customizer keys them. */
data class Palette(
    val primary: Long,
    val primaryHover: Long,
    val accent: Long,
    val background: Long,
    val surface: Long,
    val surfaceText: Long,
    val border: Long,
    val header: Long,
    val botBackground: Long,
    val botText: Long,
    val botTextSecondary: Long,
    val userBackground: Long,
    val userText: Long,
    val userTextSecondary: Long,
) {
    fun with(saved: JsonObject): Palette {
        fun pick(key: String, fallback: Long) = parseColor((saved[key] as? JsonPrimitive)?.contentOrNull) ?: fallback
        return Palette(
            primary = pick("primaryColor", primary),
            primaryHover = pick("primaryHoverColor", primaryHover),
            accent = pick("accentColor", accent),
            background = pick("backgroundColor", background),
            surface = pick("surfaceColor", surface),
            surfaceText = pick("surfaceTextColor", surfaceText),
            border = pick("borderColor", border),
            header = pick("headerColor", header),
            botBackground = pick("botMessageBackgroundColor", botBackground),
            botText = pick("botMessageTextColor", botText),
            botTextSecondary = pick("botMessageTextSecondaryColor", botTextSecondary),
            userBackground = pick("userMessageBackgroundColor", userBackground),
            userText = pick("userMessageTextColor", userText),
            userTextSecondary = pick("userMessageTextSecondaryColor", userTextSecondary),
        )
    }

    companion object {
        // The website widget's defaults: the header as the embed script draws
        // it, the rest as the conversation frame does.
        val LIGHT = Palette(
            primary = 0xFF0F172A, primaryHover = 0xFF1E293B, accent = 0xFF475569,
            background = 0xFFF8FAFC, surface = 0xFFFFFFFF, surfaceText = 0xFF64748B, border = 0xFFE2E8F0,
            header = 0xFF64748B,
            botBackground = 0xFFFFFFFF, botText = 0xFF0F172A, botTextSecondary = 0xFF64748B,
            userBackground = 0xFF0F172A, userText = 0xFFFFFFFF, userTextSecondary = 0xFFE2E8F0,
        )
        val DARK = Palette(
            primary = 0xFF1E293B, primaryHover = 0xFF334155, accent = 0xFF475569,
            background = 0xFF0F172A, surface = 0xFF1E293B, surfaceText = 0xFF94A3B8, border = 0xFF334155,
            header = 0xFF1E293B,
            botBackground = 0xFF1E293B, botText = 0xFFF1F5F9, botTextSecondary = 0xFF94A3B8,
            userBackground = 0xFF334155, userText = 0xFFF1F5F9, userTextSecondary = 0xFFCBD5E1,
        )

        /** `#rgb`, `#rrggbb` or `#rrggbbaa`, as the customizer saves them, to ARGB. */
        fun parseColor(value: String?): Long? {
            var hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
            val rgba = when (hex.length) {
                6 -> hex + "FF"
                8 -> hex
                else -> return null
            }.toLongOrNull(16) ?: return null
            return ((rgba and 0xFF) shl 24) or (rgba ushr 8)
        }
    }
}
