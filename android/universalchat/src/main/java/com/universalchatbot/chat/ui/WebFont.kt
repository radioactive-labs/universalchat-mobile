package com.universalchatbot.chat.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.universalchatbot.chat.core.GoogleFontCss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private val http by lazy { OkHttpClient() }

/**
 * The deployment's Google font, downloaded once and kept in the app's cache,
 * so the chat reads in the same face as on the website. The system font shows
 * until it lands, and for good when it cannot be fetched.
 */
@Composable
internal fun rememberWebFont(family: String?, stylesheet: String?): FontFamily? {
    val context = LocalContext.current
    var font by remember(family, stylesheet) { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(family, stylesheet) {
        if (family != null && stylesheet != null) {
            font = runCatching { loadWebFont(context, family, stylesheet) }.getOrNull()
        }
    }
    return font
}

private suspend fun loadWebFont(context: Context, family: String, stylesheet: String): FontFamily? = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "universalchat-fonts").apply { mkdirs() }
    val slug = family.lowercase().replace(Regex("[^a-z0-9]+"), "-")
    val cached = dir.listFiles { file -> file.name.startsWith("$slug-") && file.name.endsWith(".ttf") }.orEmpty()

    val files = cached.associateBy { it.name.removePrefix("$slug-").removeSuffix(".ttf").toInt() }.ifEmpty {
        val css = http.newCall(Request.Builder().url(stylesheet).build()).execute().use { it.body?.string().orEmpty() }
        GoogleFontCss.files(css).mapValues { (weight, url) ->
            File(dir, "$slug-$weight.ttf").also { file ->
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    file.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                }
            }
        }
    }
    if (files.isEmpty()) return@withContext null
    FontFamily(files.map { (weight, file) -> Font(file, FontWeight(weight)) })
}
