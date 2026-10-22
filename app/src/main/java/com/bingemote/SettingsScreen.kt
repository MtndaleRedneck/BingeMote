package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    remote: RemoteState,
    scrollState: ScrollState,
    pickerListState: LazyListState,
    onBack: () -> Unit,
    onRepair: () -> Unit,
    onFeedback: () -> Unit,
) {
    val store = remote.store
    var mainSkip by remember { mutableStateOf(store.skipMainSec) }
    var secondarySkip by remember { mutableStateOf(store.skipSecondarySec) }
    var haptics by remember { mutableStateOf(store.haptics) }
    var flash by remember { mutableStateOf(store.buttonFlash) }
    var seekTop by remember { mutableStateOf(store.seekOnTop) }
    var reconnect by remember { mutableStateOf(store.autoReconnect) }
    var pickingMovies by remember { mutableStateOf(false) }
    if (pickingMovies) {
        FolderPickerScreen(
            remote,
            pickerListState,
            onPick = { store.moviesPath = it; pickingMovies = false },
            onCancel = { pickingMovies = false },
        )
        return
    }
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(scrollState).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("Your PC") {
            SettingsRow("PC", store.pcName.ifBlank { store.host })
            Spacer(Modifier.height(6.dp))
            // No side padding, so the label lines up with "PC" above it (TextButton adds 12dp by default).
            TextButton(onClick = onRepair, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text("Pair with a different PC", color = Orange, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Skip buttons") {
            ChoiceRow(
                options = listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s"),
                selected = mainSkip,
            ) { mainSkip = it; store.skipMainSec = it }
            Spacer(Modifier.height(14.dp))
            ChoiceRow(
                options = listOf(15 to "15s", 30 to "30s", 60 to "1m", 120 to "2m", 180 to "3m"),
                selected = secondarySkip,
            ) { secondarySkip = it; store.skipSecondarySec = it }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Assigned Movies folder") {
            // Read-only: Browse PC folders is the only way to change it, so a typo can't break the Movies tab.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card2).padding(horizontal = 16.dp, vertical = 15.dp),
            ) {
                Text(
                    store.moviesPath.ifBlank { "Not set yet. Tap Browse PC folders." },
                    color = if (store.moviesPath.isBlank()) Muted else TextC,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            PressTile(
                onTap = { pickingMovies = true },
                background = OrangeGradient,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                corner = 14.dp,
            ) {
                Text(
                    "Browse PC folders",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feel") {
            ToggleRow("Vibrate on press", haptics) { haptics = it; store.haptics = it }
            Spacer(Modifier.height(14.dp))
            Text("Button flash", color = TextC, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                options = listOf(0 to "Skip rings only", 1 to "All buttons"),
                selected = flash,
            ) { flash = it; store.buttonFlash = it }
            Spacer(Modifier.height(14.dp))
            Text("Seek bar position", color = TextC, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                options = listOf(0 to "Bottom", 1 to "Top"),
                selected = if (seekTop) 1 else 0,
            ) { seekTop = it == 1; store.seekOnTop = seekTop }
            Spacer(Modifier.height(14.dp))
            ToggleRow("Reconnect on its own", reconnect) { reconnect = it; store.autoReconnect = it }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Phone can't connect?") {
            Text(
                "Open BingeMote on the PC and tap \u201CThis is my home Wi-Fi\u201D.",
                color = TextC, fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feedback & bugs") {
            val view = androidx.compose.ui.platform.LocalView.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        tapHaptic(view, haptics)
                        onFeedback()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No hard line break: the text wraps on its own at any font size.
                Text(
                    "Send feedback, report a bug, or suggest a feature",
                    color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("You see exactly what is sent before you send it.", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Updates") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            PressTile(
                onTap = {
                    if (checking) return@PressTile
                    checking = true
                    result = ""
                    scope.launch {
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            Updater.checkDetailed(BuildConfig.VERSION_NAME)
                        }
                        checking = false
                        when (outcome) {
                            is Updater.CheckOutcome.Newer -> {
                                result = "Version " + outcome.info.versionName + " is available."
                                ManualUpdate.offer = outcome.info
                            }
                            Updater.CheckOutcome.UpToDate -> result = "You're up to date."
                            Updater.CheckOutcome.Unavailable -> result = "Couldn't check right now. Try again later."
                        }
                    }
                },
                background = OrangeGradient,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                corner = 14.dp,
                enabled = !checking,
            ) {
                Text(
                    if (checking) "Checking\u2026" else "Check for updates",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(result, color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
        }
        // Bottom gap = the screen's own 16.dp padding, same as the sides (the old
        // extra 28.dp spacer left a visibly bigger gap under the last card).
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
        if (title.isNotEmpty()) {
            Text(title, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
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
    // The whole row toggles, not just the switch.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextC, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
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
