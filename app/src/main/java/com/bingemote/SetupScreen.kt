package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup as a real wizard, one step on screen at a time:
 * 1) install the helper on the PC, 2) find the PC and pair with its 6-digit code,
 * 3) VLC password + movies folder. Back always works, and a saved pairing can be
 * continued or discarded - nobody gets stuck between steps.
 */
@Composable
fun SetupScreen(remote: RemoteState, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    val store = remote.store
    val scope = rememberCoroutineScope()

    // Re-entering setup with a saved pairing opens on the pair step, which offers to continue with it.
    var step by remember { mutableStateOf(if (store.helperToken.isBlank()) 1 else 2) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<DiscoveredHelper>>(emptyList()) }
    var chosen by remember { mutableStateOf<DiscoveredHelper?>(null) }
    var manualHost by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var vlcPassword by remember { mutableStateOf("") }
    var moviesPath by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun say(text: String, isError: Boolean) { message = text; messageIsError = isError }

    // Back walks the steps, and on step 1 leaves setup entirely when there is a
    // pairing to fall back on (re-entry from Settings offers Cancel).
    BackHandler(enabled = step > 1 || onCancel != null) {
        if (step > 1) { step -= 1; say("", false) } else onCancel?.invoke()
    }

    fun scan() {
        scanning = true
        say("", false)
        scope.launch {
            found = withContext(Dispatchers.IO) { HelperClient.discover() }
            scanning = false
            if (found.isEmpty()) say("No PC answered. Check BingeMote is running on the PC, then try again - or type the PC's address below.", true)
        }
    }

    fun pair() {
        val target = chosen ?: DiscoveredHelper(manualHost.trim(), HelperClient.DEFAULT_PORT, "PC")
        if (target.host.isBlank()) { say("Enter your PC's address or pick it above.", true); return }
        if (code.length != 6) { say("Enter the 6-digit code BingeMote is showing on your PC.", true); return }
        pairing = true
        say("", false)
        scope.launch {
            try {
                val (token, name) = withContext(Dispatchers.IO) { HelperClient.pair(target.host, target.port, code) }
                store.host = target.host
                store.helperPort = target.port
                store.helperToken = token
                store.pcName = name.ifBlank { target.name }
                pairing = false
                say("", false)
                step = 3
            } catch (e: HelperClient.BadPairCode) {
                pairing = false
                say("That code didn't work. Get a fresh one: on your PC, open BingeMote from the icon by the clock, type a name under Pair new phone, and tap Pair.", true)
            } catch (e: Exception) {
                pairing = false
                say("Couldn't reach BingeMote on ${target.host}. Check the PC is awake and try again.", true)
            }
        }
    }

    fun startOver() {
        store.host = ""
        store.helperToken = ""
        store.pcName = ""
        code = ""
        chosen = null
        manualHost = ""
        found = emptyList()
        say("Pairing cleared. On your PC, open BingeMote from the icon by the clock, type a name under Pair new phone, tap Pair, then enter the new code here.", false)
    }

    fun finish() {
        if (vlcPassword.isBlank()) { say("Enter the password you set in VLC's web interface.", true); return }
        if (store.helperToken.isBlank()) { say("Pair with your PC first.", true); return }
        store.vlcPassword = vlcPassword
        store.moviesPath = moviesPath.trim().ifBlank { store.moviesPath }
        testing = true
        say("", false)
        scope.launch {
            val vlcOk = try {
                withContext(Dispatchers.IO) { Vlc.status(store.host, store.vlcPort, store.vlcPassword) }
                true
            } catch (e: Exception) { false }
            val helperOk = try {
                withContext(Dispatchers.IO) { HelperClient.ping(store.host, store.helperPort, store.helperToken) }
            } catch (e: Exception) { false }
            testing = false
            when {
                vlcOk && helperOk -> { store.setupDone = true; onDone() }
                !vlcOk && helperOk -> say("BingeMote answers, but VLC didn't. Check the password and that VLC is open with the web interface on.", true)
                vlcOk && !helperOk -> say("VLC answers, but BingeMote didn't. Mouse mode won't work until BingeMote is running on the PC.", true)
                else -> say("Nothing answered. Check the PC is awake, VLC is open, and both are on the same Wi-Fi.", true)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Set up BingeMote", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (onCancel != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel", color = Orange) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Step $step of 3",
            color = Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(18.dp))

        when (step) {
            1 -> Column {
                SetupCard {
                    Text("1. On your PC, download the BingeMote PC installer from the BingeMote release page and run it.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("2. Windows may warn about an unrecognized app. Click \"More info\", then \"Run anyway\". BingeMote PC is built by the same project as this app.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("3. The installer asks for Windows permission once during install - that is the only admin prompt, and it only opens BingeMote to your home network.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("4. Leave BingeMote running (it sits in the tray, by the clock, and starts with Windows). It shows a 6-digit code for the next step.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(18.dp))
                OrangeButton("BingeMote is running", onClick = { say("", false); step = 2 })
            }

            2 -> Column {
                if (store.helperToken.isNotBlank()) {
                    SetupCard {
                        Text("Already paired" + (if (store.pcName.isNotBlank()) " with ${store.pcName}" else ""), color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("This phone paired with your PC earlier. Continue with it, or start over to pair again with a new code.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        OrangeButton("Continue with this PC", onClick = { say("", false); step = 3 })
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { startOver() }) { Text("Start over with a new code", color = Muted) }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                SetupCard {
                    Text("Find your PC", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OrangeButton(if (scanning) "Looking..." else "Find my PC", enabled = !scanning, onClick = { scan() })
                    if (scanning) {
                        Spacer(Modifier.height(10.dp))
                        CircularProgressIndicator(color = Orange, modifier = Modifier.size(28.dp))
                    }
                    found.forEach { h ->
                        Spacer(Modifier.height(8.dp))
                        val isSel = chosen == h
                        PressTile(
                            onTap = { chosen = h; manualHost = "" },
                            background = if (isSel) OrangeGradient else CardGradient,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(
                                "${h.name}  (${h.host})",
                                color = if (isSel) androidx.compose.ui.graphics.Color(0xFF1B1B1E) else TextC,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Or type the PC's address:", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it; if (it.isNotBlank()) chosen = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("192.168.1.50", color = Dimmed) },
                        colors = fieldColors(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                SetupCard {
                    Text("Pair", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Enter the 6-digit code BingeMote is showing on your PC. It only works once. No code on screen? Open BingeMote from the icon by the clock, type a name under Pair new phone, and tap Pair.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("6-digit code", color = Dimmed) },
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OrangeButton(if (pairing) "Pairing..." else "Pair", enabled = !pairing, onClick = { pair() })
                }
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { say("", false); step = 1 }) { Text("Back", color = Muted) }
            }

            else -> Column {
                SetupCard {
                    Text("Turn on VLC's web remote", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("1. Open VLC on your PC.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("2. Tools - Preferences - at the bottom left, under \"Show settings\", pick All.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("3. Go to Interface - Main interfaces and tick Web.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("4. Under Main interfaces open Lua, and in Lua HTTP set a password. Type the same one below.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("5. Save and restart VLC. If Windows asks about the firewall, allow Private networks.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(14.dp))
                SetupCard {
                    Text("VLC password", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vlcPassword,
                        onValueChange = { vlcPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("The password from step 4", color = Dimmed) },
                        colors = fieldColors(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Movies folder (optional)", color = TextC, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = moviesPath,
                        onValueChange = { moviesPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("C:\\Users\\you\\Videos", color = Dimmed) },
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OrangeButton(if (testing) "Checking..." else "Finish", enabled = !testing, onClick = { finish() })
                }
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = { say("", false); step = 2 }) { Text("Back", color = Muted) }
            }
        }

        if (message != null && message!!.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(message!!, color = if (messageIsError) ErrorRed else Orange, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardGradient).padding(16.dp),
        content = content,
    )
}

@Composable
private fun OrangeButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    PressTile(
        onTap = { if (enabled) onClick() },
        background = if (enabled) OrangeGradient else CardGradient,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        corner = 16.dp,
    ) {
        Text(label, color = if (enabled) androidx.compose.ui.graphics.Color(0xFF1B1B1E) else Dimmed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
