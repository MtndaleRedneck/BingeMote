package com.bingemote

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
 * First-run setup, one screen in four steps:
 * 1) install the helper on the PC, 2) find the PC on the network,
 * 3) pair with the 6-digit code the helper shows, 4) VLC password + movies folder.
 */
@Composable
fun SetupScreen(remote: RemoteState, onDone: () -> Unit) {
    val store = remote.store
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(1) }
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

    fun scan() {
        scanning = true
        say("", false)
        scope.launch {
            found = withContext(Dispatchers.IO) { HelperClient.discover() }
            scanning = false
            if (found.isEmpty()) say("No PC answered. Check the helper is running, then try again - or type the PC's address below.", true)
        }
    }

    fun pair() {
        val target = chosen ?: DiscoveredHelper(manualHost.trim(), HelperClient.DEFAULT_PORT, "PC")
        if (target.host.isBlank()) { say("Enter your PC's address or pick it above.", true); return }
        if (code.length != 6) { say("Enter the 6-digit code the helper is showing on your PC.", true); return }
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
                step = 3
            } catch (e: HelperClient.BadPairCode) {
                pairing = false
                say("That code didn't work. Check the code on your PC and try again.", true)
            } catch (e: Exception) {
                pairing = false
                say("Couldn't reach the helper on ${target.host}. Check the PC is awake and try again.", true)
            }
        }
    }

    fun finish() {
        if (vlcPassword.isBlank()) { say("Enter the password you set in VLC's web interface.", true); return }
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
                !vlcOk && helperOk -> say("The helper answers, but VLC didn't. Check the password and that VLC is open with the web interface on.", true)
                vlcOk && !helperOk -> say("VLC answers, but the helper didn't. Mouse mode won't work until the helper is running.", true)
                else -> say("Nothing answered. Check the PC is awake, VLC is open, and both are on the same Wi-Fi.", true)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Set up BingeMote", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            when (step) {
                1 -> "Step 1 of 3 - the PC helper"
                2 -> "Step 2 of 3 - pair with your PC"
                else -> "Step 3 of 3 - VLC"
            },
            color = Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(18.dp))

        when (step) {
            1 -> {
                SetupCard {
                    Text("1. On your PC, download BingeMoteHelper.exe from the BingeMote release page and run it.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("2. Windows may warn about an unrecognized app. Click \"More info\", then \"Run anyway\". The helper is built by the same project as this app.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("3. The first time, Windows asks for permission to add a firewall rule. Click Yes - this is the only admin prompt, and it only opens the helper to your home network.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("4. Leave the helper running (it sits in the tray and starts with Windows). It shows a 6-digit code for the next step.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(18.dp))
                OrangeButton("The helper is running", onClick = { step = 2 })
            }
            2 -> {
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
                    Text("Enter the 6-digit code the helper is showing on your PC. It only works once.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
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
            }
            else -> {
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
