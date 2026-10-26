package com.universalchatbot.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalchatbot.chat.core.Palette
import com.universalchatbot.chat.core.ThemeMode
import com.universalchatbot.chat.core.WidgetStyle

/** One theme's colours, from the deployment's widget settings. */
@Immutable
internal data class ChatColors(
    val primary: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceText: Color,
    val border: Color,
    val header: Color,
    val botBackground: Color,
    val botText: Color,
    val botTextSecondary: Color,
    val userBackground: Color,
    val userText: Color,
    val userTextSecondary: Color,
    /** Links keep the website widget's fixed blue, lighter in dark mode. */
    val link: Color,
    val codeBackground: Color,
)

/** Everything the screen draws with: colours, the text sizes and corner radius the deployment chose, and its font. */
@Immutable
internal data class ChatLook(
    val colors: ChatColors,
    val dark: Boolean,
    val fontFamily: FontFamily,
    val base: TextUnit,
    val small: TextUnit,
    val tiny: TextUnit,
    val radius: Dp,
    val title: String,
    val subtitle: String,
)

internal val LocalChatLook = staticCompositionLocalOf {
    ChatLook(colorsOf(Palette.LIGHT, false), false, FontFamily.Default, 16.sp, 13.7.sp, 11.4.sp, 16.dp, WidgetStyle.DEFAULT_TITLE, WidgetStyle.DEFAULT_SUBTITLE)
}

private fun colorsOf(palette: Palette, dark: Boolean) = ChatColors(
    primary = Color(palette.primary),
    accent = Color(palette.accent),
    background = Color(palette.background),
    surface = Color(palette.surface),
    surfaceText = Color(palette.surfaceText),
    border = Color(palette.border),
    header = Color(palette.header),
    botBackground = Color(palette.botBackground),
    botText = Color(palette.botText),
    botTextSecondary = Color(palette.botTextSecondary),
    userBackground = Color(palette.userBackground),
    userText = Color(palette.userText),
    userTextSecondary = Color(palette.userTextSecondary),
    link = if (dark) Color(0xFF60A5FA) else Color(0xFF3B82F6),
    codeBackground = if (dark) Color(0xFF1F2937) else Color(0xFFF3F4F6),
)

/**
 * Whether to draw dark: the visitor's own choice from the header toggle when
 * they made one, otherwise the deployment's theme, following the phone for
 * "system".
 */
@Composable
internal fun resolveDark(style: WidgetStyle, override: String?): Boolean = when (override) {
    "dark" -> true
    "light" -> false
    else -> when (style.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}

@Composable
internal fun ChatTheme(style: WidgetStyle, dark: Boolean, fontFamily: FontFamily?, content: @Composable () -> Unit) {
    val colors = colorsOf(style.palette(dark), dark)
    val look = ChatLook(
        colors = colors,
        dark = dark,
        fontFamily = fontFamily ?: FontFamily.Default,
        base = style.fontSize.sp,
        small = style.smallFontSize.sp,
        tiny = style.tinyFontSize.sp,
        radius = style.borderRadius.dp,
        title = style.title,
        subtitle = style.subtitle,
    )
    val scheme = if (dark) {
        darkColorScheme(primary = colors.primary, background = colors.background, surface = colors.surface)
    } else {
        lightColorScheme(primary = colors.primary, background = colors.background, surface = colors.surface)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalChatLook provides look, content = content)
    }
}
