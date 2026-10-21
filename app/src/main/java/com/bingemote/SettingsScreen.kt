package com.bingemote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(remote: RemoteState, onBack: () -> Unit, onRepair: () -> Unit) {
    val store = remote.store
    var mainSkip by remember { mutableStateOf(store.skipMainSec) }
    var secondarySkip by remember { mutableStateOf(store.skipSecondarySec) }
    var haptics by remember { mutableStateOf(store.haptics) }
    var reconnect by remember { mutableStateOf(store.autoReconnect) }
    var pickingMovies by remember { mutableStateOf(false) }
    if (pickingMovies) {
        FolderPickerScreen(
            remote,
            onPick = { store.moviesPath = it; pickingMovies = false },
            onCancel = { pickingMovies = false },
        )
        return
    }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))

        SettingsSection("Your PC") {
            SettingsRow("PC", store.pcName.ifBlank { store.host })
            SettingsRow("BingeMote PC", "${store.host}:${store.helperPort}")
            SettingsRow("VLC", "${store.host}:${store.vlcPort}")
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRepair) { Text("Pair with a different PC", color = Orange) }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Skip buttons") {
            Text("Main buttons", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "60s"),
                selected = mainSkip,
            ) { mainSkip = it; store.skipMainSec = it }
            Spacer(Modifier.height(12.dp))
            Text("Smaller buttons", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(15 to "15s", 30 to "30s", 60 to "1m", 120 to "2m", 180 to "3m", 300 to "5m"),
                selected = secondarySkip,
            ) { secondarySkip = it; store.skipSecondarySec = it }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Movies folder") {
            var path by remember(store.moviesPath) { mutableStateOf(store.moviesPath) }
            OutlinedTextField(
                value = path,
                onValueChange = { path = it; store.moviesPath = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("C:\\Users\\you\\Videos", color = Dimmed) },
                colors = fieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            PressTile(
                onTap = { pickingMovies = true },
                background = OrangeGradient,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                corner = 14.dp,
            ) {
                Text(
                    "Browse PC folders...",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("The folder on your PC that the Movies screen opens. Browse to pick it, or type it above.", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feel") {
            var speed by remember { mutableStateOf(store.mouseSpeed) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pointer speed", color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("%.1fx".format(speed), color = Muted, fontSize = 14.sp)
            }
            Slider(
                value = speed,
                onValueChange = { speed = it; store.mouseSpeed = it },
                valueRange = 0.5f..3.0f,
                colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange, inactiveTrackColor = TrackGray),
            )
            Text("Lower is slower. If the pointer feels jumpy, bring this down.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            ToggleRow("Vibrate on press", haptics) { haptics = it; store.haptics = it }
            ToggleRow("Reconnect on its own", reconnect) { reconnect = it; store.autoReconnect = it }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Updates") {
            Text(
                "BingeMote checks for a new version each time it opens and offers it to you. Nothing installs on its own.",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("This is version ${BuildConfig.VERSION_NAME}.", color = Dimmed, fontSize = 12.sp)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextC, unfocusedTextColor = TextC,
    focusedBorderColor = Orange, unfocusedBorderColor = TrackGray,
    cursorColor = Orange,
)

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardGradient).padding(16.dp),
    ) {
        Text(title, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.width(80.dp))
        Text(value, color = TextC, fontSize = 14.sp)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Orange, uncheckedTrackColor = TrackGray),
        )
    }
}

@Composable
fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            PressTile(
                onTap = { onPick(value) },
                background = if (isSel) OrangeGradient else CardGradient,
                modifier = Modifier.weight(1f).height(44.dp),
                corner = 14.dp,
            ) {
                Text(
                    label,
                    color = if (isSel) androidx.compose.ui.graphics.Color(0xFF1B1B1E) else Muted,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
