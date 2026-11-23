package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tapping the PC pill in Settings lands here: the pairing's status at a glance, and
 * the only repair action - pair again. Not a wizard: everything is already set up, so
 * there is nothing to step through. */
@Composable
fun YourPCScreen(remote: RemoteState, scrollState: ScrollState, onBack: () -> Unit, onPairAgain: () -> Unit) {
    val store = remote.store
    val unreachable = remote.unreachable
    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(scrollState).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Your PC", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("") {
            Text(store.pcName.ifBlank { "Your PC" }, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (unreachable) ErrorRed else Color(0xFF4CAF50)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (unreachable) "Can't reach your PC" else "Connected",
                    color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (unreachable) "Attempting to reconnect in the background"
                else "Everything is set up. Nothing to do here.",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("") {
            PressTile(
                onTap = onPairAgain,
                background = Brush.verticalGradient(listOf(Color(0xFF36363F), Color(0xFF36363F))),
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .border(0.7.dp, Color(0xFFA2A2AD), RoundedCornerShape(14.dp)),
                corner = 14.dp,
            ) {
                Text("Pair again", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Only needed for a new phone, or if this phone was removed on the PC.",
                color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
    }
}
