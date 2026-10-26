package com.universalchatbot.chat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStyleTest {
    private fun parse(json: String) = WidgetStyle.parse(Json.parseToJsonElement(json).jsonObject, null)

    @Test
    fun `nothing saved reads as the website widget's defaults`() {
        val style = parse("{}")

        assertEquals(ThemeMode.LIGHT, style.theme)
        assertEquals(Palette.LIGHT, style.light)
        assertEquals(Palette.DARK, style.dark)
        assertEquals(16f, style.fontSize)
        assertEquals(16f, style.borderRadius)
        assertNull(style.fontFamily)
        assertEquals("Chat with us", style.title)
    }

    @Test
    fun `saved colours override per theme and key, in any hex form`() {
        val style = parse("""{"lightColors":{"userMessageBackgroundColor":"#4f46e5","headerColor":"#abc","borderColor":"#11223380"},"darkColors":{"botMessageTextColor":"#ffffff"}}""")

        assertEquals(0xFF4F46E5, style.light.userBackground)
        assertEquals(0xFFAABBCC, style.light.header)
        assertEquals(0x80112233, style.light.border)
        assertEquals(Palette.LIGHT.botText, style.light.botText)
        assertEquals(0xFFFFFFFF, style.dark.botText)
    }

    @Test
    fun `theme, sizes and the font family read as the widget reads them`() {
        val style = parse("""{"theme":"system","fontSize":"18px","borderRadius":"0.5rem","fontFamily":"'Poppins', sans-serif"}""")

        assertEquals(ThemeMode.SYSTEM, style.theme)
        assertEquals(18f, style.fontSize)
        assertEquals(18f * 0.857f, style.smallFontSize)
        assertEquals(8f, style.borderRadius)
        assertEquals("Poppins", style.fontFamily)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("tailwind_class"))
        assertNull(WidgetStyle.firstFamily("ui-sans-serif, system-ui, sans-serif"))
    }

    @Test
    fun `a Google stylesheet yields one upright file per weight`() {
        val css = """
            @font-face { font-family: 'Poppins'; font-style: italic; font-weight: 400; src: url(https://x/i.ttf) format('truetype'); }
            @font-face { font-family: 'Poppins'; font-style: normal; font-weight: 400; src: url(https://x/400.ttf) format('truetype'); }
            @font-face { font-family: 'Poppins'; font-style: normal; font-weight: 600; src: url(https://x/600.ttf) format('truetype'); }
        """

        assertEquals(mapOf(400 to "https://x/400.ttf", 600 to "https://x/600.ttf"), GoogleFontCss.files(css))
    }
}
