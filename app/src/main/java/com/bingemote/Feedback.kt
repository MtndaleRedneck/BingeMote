package com.bingemote

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val REPO_ISSUES = "https://github.com/MtndaleRedneck/BingeMote/issues"
/** A plain Google Form, like BumpLog's: the app opens it pre-filled, he reviews and submits in the browser. */
const val BUG_FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSeD1ytVTxoA0GgftLr5wSu2kZm1yxRwCHcOV4Pb100Lm6hLcQ/viewform"
private const val MAX_TEXT = 3000
private const val MAX_ENCODED = 4500

/** The only things a feedback report carries besides what he typed. No PC name, Wi-Fi names, paired phones or file paths. */
data class FeedbackContext(val appVersion: String, val androidVersion: String, val phoneModel: String, val display: String = "")

object Feedback {
    /** Trims the text so the encoded link stays well under the roughly 8 KB web servers accept. The preview shows this same trimmed text. */
    fun clean(text: String): String {
        var t = text.trim().take(MAX_TEXT)
        while (t.isNotEmpty() && enc(t).length > MAX_ENCODED) t = t.dropLast(50).trimEnd()
        return t
    }

    /** Exactly what is sent, shown before he taps. */
    fun preview(text: String, c: FeedbackContext): String =
        "Message:\n${clean(text).ifEmpty { "(nothing written yet)" }}\n\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}${displayLine(c)}"

    private fun displayLine(c: FeedbackContext) = if (c.display.isBlank()) "" else "\nDisplay: ${c.display}"

    fun githubUrl(text: String, c: FeedbackContext): String {
        val t = clean(text)
        val title = "Feedback: " + t.lineSequence().firstOrNull().orEmpty().take(60)
        val body = "$t\n\n---\nApp version: ${c.appVersion}\nAndroid version: ${c.androidVersion}\nPhone model: ${c.phoneModel}${displayLine(c)}"
        return "$REPO_ISSUES/new?title=${enc(title)}&body=${enc(body)}"
    }

    fun formUrl(text: String, c: FeedbackContext): String =
        "$BUG_FORM_URL?usp=pp_url&entry.1980878269=${enc(clean(text) + (if (c.display.isBlank()) "" else "\n\nDisplay: ${c.display}"))}&entry.857472167=${enc(c.phoneModel)}" +
            "&entry.2017214675=${enc(c.androidVersion)}&entry.446486963=${enc(c.appVersion)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun context(ctx: android.content.Context) = FeedbackContext(
        BuildConfig.VERSION_NAME,
        "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
        displayInfo(ctx),
    )

    /** font scale, density and window size - the bits that explain a layout bug. */
    private fun displayInfo(ctx: android.content.Context): String = try {
        val cfg = ctx.resources.configuration
        val dm = ctx.resources.displayMetrics
        val dpiName = when {
            dm.densityDpi <= 240 -> "hdpi"
            dm.densityDpi <= 320 -> "xhdpi"
            dm.densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        "font scale %.2f, %d dpi (%s), screen %dx%d dp".format(
            cfg.fontScale, dm.densityDpi, dpiName, cfg.screenWidthDp, cfg.screenHeightDp)
    } catch (e: Exception) { "" }
}

@Composable
fun FeedbackScreen(scrollState: ScrollState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val c = remember { Feedback.context(ctx) }
    fun open(url: String) = ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(scrollState).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Feedback", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("") {
            Text("Tell us what is on your mind: a bug, an idea, or general feedback. Reports may become public issues on GitHub, so leave out personal details.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                text, { text = it.take(MAX_TEXT) },
                label = { Text("Your message") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("What will be sent") {
            Text(Feedback.preview(text, c), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("No PC name, Wi-Fi names, paired phone names, or file paths are included. Nothing is sent until you tap a button below, and then it opens in your browser for you to review and submit.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("GitHub issue") {
            Text("Public and easiest to follow up.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            FeedbackButton("Open GitHub issue") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.githubUrl(text, c))
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Private form") {
            Text("No name needed. A reply email is optional.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            FeedbackButton("Open private form") {
                if (text.isBlank()) Toast.makeText(ctx, "Write your message first.", Toast.LENGTH_SHORT).show()
                else open(Feedback.formUrl(text, c))
            }
        }
        Spacer(Modifier.height(14.dp))

        // Done is just Back: a quiet text button, not a third orange button competing with the two send buttons.
        androidx.compose.material3.TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Done", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The filled orange action button, app-wide style. */
@Composable
private fun FeedbackButton(label: String, onClick: () -> Unit) {
    PressTile(
        onTap = onClick,
        background = OrangeGradient,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        corner = 14.dp,
    ) {
        Text(label, color = androidx.compose.ui.graphics.Color(0xFF1B1B1E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
