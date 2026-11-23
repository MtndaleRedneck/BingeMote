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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

// One orange everywhere: the settings components use the theme's own tokens.
private val OnOrange = Color(0xFF281300)

private val ThumbDark = Color(0xFF1B1B1D)
private val PillGradient = OrangeGradient
private val SegmentOnGradient = OrangeGradient

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
    val context = LocalContext.current
    var mainSkip by remember { mutableStateOf(store.skipMainSec) }
    var secondarySkip by remember { mutableStateOf(store.skipSecondarySec) }
    var haptics by remember { mutableStateOf(store.haptics) }
    var lockControls by remember { mutableStateOf(store.lockScreenControls) }
    var permissionPopup by remember { mutableStateOf(false) }
    var showVolumeOnPc by remember { mutableStateOf(store.showVolumeOnPc) }
    var flash by remember { mutableStateOf(store.buttonFlash) }
    var cancelStyle by remember { mutableStateOf(store.cancelStyle) }
    var pickingMovies by remember { mutableStateOf(false) }
    var keepAwake by remember { mutableStateOf(store.keepAwake) }
    if (permissionPopup) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { permissionPopup = false },
            title = { Text("Enable notification controls?") },
            text = { Text("Allow notifications to show the playback controls in your notification shade.") },
            confirmButton = { TextButton(onClick = {
                permissionPopup = false
                (context as? android.app.Activity)?.let(MediaControls::requestOrOpenSettings)
            }) { Text("Enable notifications") } },
            dismissButton = { TextButton(onClick = { permissionPopup = false }) { Text("Not now") } },
        )
    }
    val configScope = rememberCoroutineScope()
    // The helper owns the real state: read it back so both toggles reflect the PC, not
    // just the phone's memory. Helpers without a key leave it absent and the phone-local
    // value stands (best-effort, same as the POST).
    LaunchedEffect(Unit) {
        val host = store.host; val port = store.helperPort; val token = store.helperToken
        configScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cfg = HelperClient.getConfig(host, port, token)
                if (cfg.has("volumeOsd")) {
                    val on = cfg.getBoolean("volumeOsd")
                    showVolumeOnPc = on; store.showVolumeOnPc = on
                }
                if (cfg.has("keepAwake")) {
                    val on = cfg.getBoolean("keepAwake")
                    keepAwake = on; store.keepAwake = on
                }
            } catch (_: Exception) { }
        }
    }
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
            HeaderIconButton(onBack, haptics) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        // Zephyr's Connected Sources treatment: the orange pill IS the pairing entry
        // point (pill + name only; the old "Pair with a different PC" text line is gone).
        SettingsSection("Your PC") {
            SourceRow("PC", store.pcName.ifBlank { store.host }, hapticsOn = haptics, onLabelClick = onRepair)
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("PC power") {
            ToggleRow("Keep PC awake while watching", keepAwake, haptics) {
                keepAwake = it; store.keepAwake = it
                val host = store.host; val port = store.helperPort; val token = store.helperToken
                configScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { HelperClient.setKeepAwake(host, port, token, it) } catch (_: Exception) { }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Screen can still turn off. Minimized VLC sleeps normally.", color = Muted, fontSize = 11.3.sp)
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
                hapticsOn = haptics,
            ) {
                Text(
                    "Browse PC folders",
                    color = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Skip buttons") {
            Text("Primary skip buttons", color = TextC, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s"),
                selected = mainSkip,
                haptics = haptics,
            ) { mainSkip = it; store.skipMainSec = it }
            Spacer(Modifier.height(14.dp))
            Text("Secondary skip buttons", color = TextC, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(15 to "15s", 30 to "30s", 60 to "1m", 120 to "2m", 180 to "3m"),
                selected = secondarySkip,
                haptics = haptics,
            ) { secondarySkip = it; store.skipSecondarySec = it }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feel") {
            ToggleRow("Vibrate on press", haptics, haptics, buzzWhenEnabling = true) { haptics = it; store.haptics = it }
            Spacer(Modifier.height(9.dp))
            ToggleRow("Notification shade controls", lockControls, haptics) {
                lockControls = it; store.lockScreenControls = it
                if (it && !MediaControls.allowed(context)) permissionPopup = true
                MediaControls.sync(context, store)
            }
            Spacer(Modifier.height(9.dp))
            ToggleRow("Show volume on PC screen", showVolumeOnPc, haptics) {
                showVolumeOnPc = it; store.showVolumeOnPc = it
                val host = store.host; val port = store.helperPort; val token = store.helperToken
                configScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { HelperClient.setVolumeOsd(host, port, token, it) } catch (_: Exception) { }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Button flash", color = TextC, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                options = listOf(1 to "All buttons", 0 to "Skip rings only"),
                selected = flash,
                haptics = haptics,
            ) { flash = it; store.buttonFlash = it }
            Spacer(Modifier.height(14.dp))
            Text("Cancel button style", color = TextC, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                options = listOf(0 to "Fills in", 1 to "Ring only"),
                selected = if (cancelStyle == "ring") 1 else 0,
                haptics = haptics,
            ) { cancelStyle = if (it == 1) "ring" else "fills"; store.cancelStyle = cancelStyle }
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
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Updates") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            PressTile(
                hapticsOn = haptics,
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardGradient).padding(16.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
fun ToggleRow(label: String, checked: Boolean, haptics: Boolean, buzzWhenEnabling: Boolean = false, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    // The whole row toggles, not just the switch.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { next ->
                    // UI spec: other toggles tick only while vibrate is ON; the Vibrate
                    // toggle ticks once when turned ON and stays silent when turned OFF.
                    tapHaptic(view, if (buzzWhenEnabling) next else haptics)
                    onChange(next)
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f))
        SettingToggle(checked)
    }
}

/** The shared settings toggle: one 190ms ease-in-out crossfade and thumb slide on every
 * settings switch, so all of them feel identical. Track 52x32dp radius 16dp; ON crossfades
 * gray into the shared OrangeGradient ombre every other filled orange surface uses (Ezzy's
 * all-orange-consistent call). Whole-dp thumb: 24dp with 4dp margins, no rim. */
@Composable
fun SettingToggle(checked: Boolean) {
    val fade by animateFloatAsState(if (checked) 1f else 0f, tween(190, easing = FastOutSlowInEasing), label = "track")
    val thumbPad by animateDpAsState(if (checked) 24.dp else 4.dp, tween(190, easing = FastOutSlowInEasing), label = "thumb")
    Box(
        Modifier
            .width(52.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TrackGray),
    ) {
        Box(Modifier.fillMaxSize().alpha(fade).background(OrangeGradient))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = thumbPad)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) ThumbDark else TextC),
        )
    }
}

/** Zephyr's Connected Sources row, ported: the orange pill (128 wide, 15dp corner, same
 * gradient/text as Zephyr's VLC PC pill) is the tappable entry point; the value sits
 * right-aligned next to it. Ezzy's call: two height families - the pill matches the
 * choice tiles (130/3 = 43.33dp), the full-width action buttons stay 48dp. */
@Composable
fun SourceRow(label: String, value: String, hapticsOn: Boolean, onLabelClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PressTile(
            onTap = onLabelClick,
            background = PillGradient,
            modifier = Modifier.width(128.dp).height((130f / 3f).dp),
            corner = 15.dp,
            hapticsOn = hapticsOn,
        ) {
            Text(label, color = OnOrange, fontSize = 16.4.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = TextC,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun ChoiceRow(options: List<Pair<Int, String>>, selected: Int, haptics: Boolean, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.3.dp)) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            PressTile(
                onTap = { onPick(value) },
                background = if (isSel) SegmentOnGradient else CardGradient,
                modifier = Modifier.weight(1f).height((130f / 3f).dp),
                corner = (40f / 3f).dp,
                hapticsOn = haptics,
            ) {
                Text(
                    label,
                    color = if (isSel) OnOrange else Muted,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
