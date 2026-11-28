package com.bingemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
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
 * First-run setup: 1) install the helper on the PC, 2) one pair screen - find the PC,
 * enter its one-time code, and the success confirmation appears right below the form,
 * with the VLC hand-off under it. no separate finish screen. Back
 * always works, and a saved pairing can be continued or replaced by a fresh code.
 */
@Composable
fun SetupScreen(
    remote: RemoteState,
    scrollState: androidx.compose.foundation.ScrollState,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    demoVlcPending: Boolean = false,
    demoPair: Boolean = false,
    demoPreselect: Boolean = false, // screenshot rig only: demo PC already chosen
    demoAuto: String = "", // screenshot rig only: ok | unreachable | refused result card with demoVlcPending
) {
    val store = remote.store
    val scope = rememberCoroutineScope()
    val demo = BuildConfig.DEBUG

    // Re-entering setup with a saved pairing opens on the pair screen. The pair-screen
    // demos open there directly even with no saved pairing.
    // B7: the pairing state is read ONCE on IO, never as a Main-thread secure read during
    // composition. Until it lands, an honest checking screen shows instead of a guessed
    // step. The screenshot rig gets a synchronous value so its captures stay deterministic.
    // ONE atomic locked capture of (token?, password?, generation): two separate getter
    // reads could mix one pairing's token with another's password. The generation rides
    // along so the Continue publication can prove the card still describes the current
    // pairing. The screenshot rig gets a synchronous value (sentinel generation -1).
    var setupPairing by remember { mutableStateOf<Triple<Boolean, Boolean, Long>?>(if ((demoPair || demoVlcPending) && demo) Triple(true, demoVlcPending, -1L) else null) }
    var pairingCheckRun by remember { mutableStateOf(0) }
    var pairingCheckExhausted by remember { mutableStateOf(false) }
    // All wizard/continuation state is hoisted ABOVE the neutral-checking gate below: a
    // temporary checking interlude (Continue on exhausted drift) used to dispose every
    // remember under the gate - step, justPaired, the entered host/code - and recreate
    // them fresh on reseed. Hoisted, they survive; fresh-screen seeding is preserved by
    // the step = 0 marker (the first verified install below seeds 1/2 from the status).
    var step by remember { mutableStateOf(if ((demoPair || demoVlcPending) && demo) 2 else 0) }   // 0 = not yet seeded; the first verified install seeds 1/2
    var scanning by remember { mutableStateOf(false) }
    var found by remember {
        mutableStateOf(if ((demoPair || demoVlcPending) && demo) listOf(DiscoveredHelper("192.168.1.50", HelperClient.DEFAULT_PORT, "MY-PC")) else emptyList())
    }
    var chosen by remember { mutableStateOf<DiscoveredHelper?>(if (((demoPair && demoPreselect) || demoVlcPending) && demo) DiscoveredHelper("192.168.1.50", HelperClient.DEFAULT_PORT, "MY-PC") else null) }
    var manualHost by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    // A code just paired on this screen: the success confirmation shows below the form.
    var justPaired by remember { mutableStateOf(demoVlcPending && demo) }
    // One-shot refresh on resume / setup transitions (no polling): reseed the displayed
    // status ONLY on drift and ONLY from a generation-verified capture; otherwise keep it.
    // The screenshot rig's synchronous seed (sentinel -1) is never replaced.
    LaunchedEffect(pairingCheckRun, step, justPaired) {
        val current = setupPairing ?: return@LaunchedEffect
        if (current.third == -1L) return@LaunchedEffect
        var cap = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
        var attempts = 0
        while (store.pairingGeneration != cap.third && attempts < 3) {
            attempts++
            cap = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
        }
        if (store.pairingGeneration == cap.third && cap.third != current.third) setupPairing = cap
    }
    var pairedName by remember { mutableStateOf(if (demoVlcPending && demo) "MY-PC" else "") }
    // An earlier pairing continued to the VLC hand-off without a fresh code.
    var continueVlc by remember { mutableStateOf(false) }
    // Automatic-first VLC setup (helper 1.7+): the PC picked the password at pairing time.
    // "checking" while the phone verifies it, then "ok" or "refused".
    var autoSetup by remember { mutableStateOf(demoVlcPending && demo) }
    var autoState by remember { mutableStateOf(if (demoVlcPending && demo) demoAuto.ifBlank { "ok" } else "checking") }
    // The PC has a VLC settings update still waiting (any reason): control starts once it's applied.
    var autoPending by remember { mutableStateOf(demoVlcPending && demo) }
    // VLC is simply closed (the PC attests the settings are written): control starts when it opens.
    var autoNotOpen by remember { mutableStateOf(false) }
    // Bump to re-run the automatic check after the PC was unreachable.
    var checkRun by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }
    // The PC scan runs once when the pair screen opens; demos skip it so the renders are stable.
    var scannedOnce by remember { mutableStateOf(demoPair || demoVlcPending) }
    // Resume triggers a ONE-SHOT re-check (no polling): the effects below.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) pairingCheckRun++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(pairingCheckRun) {
        if (setupPairing == null) {
            // The generation must be STILL LIVE on Main at the assignment: a writer landing
            // between the IO capture and the assignment would otherwise stamp a stale status
            // current. On drift, recapture (bounded). On EXHAUSTED drift nothing unverified
            // is installed: the checking screen shows a Retry instead of a forever spinner.
            pairingCheckExhausted = false
            var cap = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
            var attempts = 0
            while (store.pairingGeneration != cap.third && attempts < 3) {
                attempts++
                cap = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
            }
            if (store.pairingGeneration == cap.third) {
                setupPairing = cap
                if (step == 0) step = if (!cap.first) 1 else 2
            } else pairingCheckExhausted = true
        }
    }
    val sp = setupPairing
    if (sp == null) {
        Column(Modifier.fillMaxSize().background(Bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (pairingCheckExhausted) {
                // The bounded capture kept drifting: nothing unverified was installed, and
                // no permanent spinner - the user retries explicitly.
                Text("Couldn't check the pairing. Try again.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OrangeButton("Retry", onClick = { pairingCheckExhausted = false; pairingCheckRun++ })
            } else {
                CircularProgressIndicator(color = Orange, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text("Checking pairing…", color = Muted, fontSize = 14.sp)
            }
        }
        return
    }


    fun say(text: String, isError: Boolean) { message = text; messageIsError = isError }

    val successVisible = justPaired || continueVlc

    // The real check, run once when the success section opens after an automatic pair (and
    // again per Try again). The PC attests what is configured; a network error never becomes
    // a green Done on its own. The demo preview state starts at "ok" and skips this.
    LaunchedEffect(successVisible, autoSetup, checkRun) {
        if (!successVisible || !autoSetup || autoState != "checking") return@LaunchedEffect
        when (withContext(Dispatchers.IO) { AutoSetupCheck.run(store) }) {
            AutoSetupCheck.Outcome.OK -> { autoPending = false; autoNotOpen = false; autoState = "ok" }
            AutoSetupCheck.Outcome.OK_DEFERRED -> { autoPending = true; autoNotOpen = false; autoState = "ok" }
            AutoSetupCheck.Outcome.OK_NOT_OPEN -> { autoPending = false; autoNotOpen = true; autoState = "ok" }
            AutoSetupCheck.Outcome.REFUSED -> autoState = "refused"
            AutoSetupCheck.Outcome.PC_UNREACHABLE -> autoState = "unreachable"
            AutoSetupCheck.Outcome.SETUP_UNKNOWN -> autoState = "unknown"
            // Old helper (no setup-status route): the same update card the pair-time old-helper
            // path shows - reached via !autoSetup, so there is ONE rendering of that message.
            AutoSetupCheck.Outcome.UNSUPPORTED -> { autoSetup = false; autoState = "unsupported" }
        }
    }

    // Re-entry from Your PC has a working setup to fall back on: Back leaves instead of
    // walking to the install step. First run: Back walks pair -> install.
    val reentry = store.setupDone && onCancel != null
    BackHandler(enabled = step > 1 || onCancel != null) {
        if (step > 1 && !reentry) { step -= 1; say("", false) } else onCancel?.invoke()
    }

    fun scan() {
        scanning = true
        discoveryMessage = null
        say("", false)
        scope.launch {
            found = withContext(Dispatchers.IO) { HelperClient.discover() }
            scanning = false
            if (found.isEmpty()) discoveryMessage = "No PC answered. Check BingeMote is running on the PC, then try again, or type its address."
        }
    }

    // Auto-scan once when the pair screen opens; the button re-scans on demand.
    LaunchedEffect(step) {
        if (step == 2 && !scannedOnce) { scannedOnce = true; scan() }
    }

    fun pair() {
        val target = chosen ?: run { say("Select your PC first.", true); return }
        if (code.length != 4 && code.length != 6) { say("Enter the code BingeMote is showing on your PC - 4 digits, or 6 on an older BingeMote.", true); return }
        pairing = true
        say("", false)
        scope.launch {
            try {
                val sp = withContext(Dispatchers.IO) { HelperClient.pairSecure(target.host, target.port, code) }
                val paired = sp.result
                // The save's outcome and its post-bump generation come from the SAME locked
                // section: the generation is bound to THIS save, never a blind later read.
                val (saveOutcome, savedGen) = withContext(Dispatchers.IO) {
                    store.savePairingAndGeneration(target.host, target.port,
                        paired.name.ifBlank { target.name }, paired.vlcPassword, sp.proof, sp.pcId)
                }
                if (saveOutcome != SecurePairSave.Outcome.SAVED || savedGen == null) {
                    pairing = false; say(saveOutcome.userMessage, true); return@launch
                }
                // The same atomic publish gate as the other live-address sites: a mutation
                // landing before or after the save drops this publish instead of letting the
                // old tuple through.
                store.runIfPairingCurrent(savedGen) { remote.publishAddress(target.host, target.port, paired.token) }
                // Automatic-first: the PC generated the VLC password and handed it over with
                // the pair. Older helpers need an update before automatic setup can finish.
                if (paired.vlcPassword != null) {
                    // The password is already durably saved: SecurePairSave.saveInner wrote it via the
                    // store adapter (flush + read-back verified) before SAVED was returned. autoPending
                    // needs no seed here either: autoState "checking" runs AutoSetupCheck first, and every
                    // outcome that can show the pending note sets autoPending itself.
                    autoSetup = true
                    autoState = "checking"
                } else if (paired.vlcPending) {
                    // NEW helper, but it could not hand over a verified or durably-owed password
                    // with the pair (a vlcrc failure at the commit boundary - the explicit-null
                    // reply, never a phantom). The SAME check finishes setup truthfully:
                    // setup-status attests the owed write pending (deferred) or the one adopt
                    // heal fetches the PC's copy - never the "update your PC" card, this helper
                    // is not old.
                    autoSetup = true
                    autoState = "checking"
                } else {
                    autoSetup = false
                    autoState = "unsupported"
                }
                pairing = false
                pairedName = store.pcName
                justPaired = true
                say("", false)
            } catch (e: HelperClient.KeyChanged) {
                pairing = false
                say(e.msg, true)
            } catch (e: HelperClient.BadPairCode) {
                pairing = false
                say("That code didn't work. Get a fresh one: on your PC, open BingeMote from the icon by the clock, type a name under Pair new phone, and tap Pair.", true)
            } catch (e: Exception) {
                pairing = false
                say("Couldn't reach BingeMote on ${target.host}. Check the PC is awake and try again.", true)
            }
        }
    }

    // A refused automatic password: back to the code field for a fresh pair. The saved
    // pairing stays until the new one lands - nothing working is thrown away early.
    fun retryPair() {
        code = ""
        justPaired = false
        autoSetup = false
        continueVlc = false
        say("", false)
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(scrollState).padding(16.dp), // 16dp like every other screen
    ) {
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Set up BingeMote", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (onCancel != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel", color = AccentText) }
            }
        }
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
                    Text("4. Leave BingeMote running (it sits in the tray, by the clock, and starts with Windows). Open it, type a name under Pair new phone, and tap Pair - it shows a code for the next step.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(18.dp))
                OrangeButton("BingeMote is running", onClick = { say("", false); step = 2 })
                SetupMessage(message, messageIsError)
            }

            else -> Column {
                // Mid-setup recovery only: a pairing exists but setup never finished, so
                // offer continuing to the VLC step. A finished setup re-entering from
                // Your PC -> Pair again gets just the pair form - no wizard loop.
                if (sp.first && !store.setupDone && !justPaired) {
                    SetupCard {
                        Text("Already paired" + (if (store.pcName.isNotBlank()) " with ${store.pcName}" else ""), color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Continue to the VLC step below, or enter a new code to pair again - only needed for a new phone, or if this phone was removed on the PC.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        OrangeButton("Continue", onClick = {
                            say("", false)
                            // ONE fresh atomic capture on IO; the state-set order is exactly
                            // preserved (autoSetup, autoState, continueVlc, checkRun). If the
                            // pairing DRIFTED since the card was drawn, reseed from the fresh
                            // capture instead of publishing from a stale one.
                            scope.launch {
                                // The generation must be STILL LIVE at the actual Main
                                // state-set: a writer landing between the IO capture and
                                // the publish would make this a stale seed. On EXHAUSTED
                                // drift, drop to the neutral checking/retry path (a fresh
                                // run) - the unverified capture is never installed.
                                var cur = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
                                var attempts = 0
                                while (store.pairingGeneration != cur.third && attempts < 3) {
                                    attempts++
                                    cur = withContext(Dispatchers.IO) { store.pairingStatusWithGeneration() }
                                }
                                if (store.pairingGeneration != cur.third) { setupPairing = null; pairingCheckRun++; return@launch }
                                if (sp.third != -1L && cur.third != sp.third) { setupPairing = cur; return@launch }
                                // Paired-but-no-password is NOT "old helper" by itself: a new
                                // helper can finish a pair without a verified password (the
                                // explicit-null recovery reply). Run the check and let the LIVE
                                // PC sort it: the missing status route maps to UNSUPPORTED (the
                                // update card, as before); a pending write defers; an applied
                                // password is adopted by the heal.
                                autoSetup = cur.first
                                autoState = if (autoSetup) "checking" else "unsupported"
                                continueVlc = true
                                checkRun++
                            }
                        })
                    }
                    Spacer(Modifier.height(14.dp))
                }
                SetupCard {
                    Text("Find your PC", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OrangeButton(if (scanning) "Looking\u2026" else "Find my PC", enabled = !scanning, onClick = { scan() })
                    if (scanning) {
                        Spacer(Modifier.height(10.dp))
                        CircularProgressIndicator(color = Orange, modifier = Modifier.size(28.dp))
                    }
                    found.forEach { h ->
                        Spacer(Modifier.height(8.dp))
                        val isSel = chosen == h
                        PressTile(
                            onTap = { chosen = h; manualHost = ""; code = ""; justPaired = false; continueVlc = false; say("", false) },
                            background = if (isSel) OrangeGradient else CardGradient,
                            // Unselected rows carry the orange outline so they read as tappable
                            // (approved); the selected row keeps the solid orange fill.
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                                .then(if (isSel) Modifier else Modifier.border(1.dp, Orange, RoundedCornerShape(18.dp))),
                        ) {
                            Text(
                                "${h.name}  (${h.host})",
                                color = if (isSel) OnAccent else TextC,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Or type the PC's address:", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it; chosen = null; code = ""; justPaired = false; continueVlc = false; discoveryMessage = null; say("", false) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("192.168.1.50", color = Muted) },
                        // Numbers-and-dots keyboard for an address.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OrangeButton("Use this address", enabled = manualHost.isNotBlank(), onClick = {
                        val address = manualHost.trim()
                        val host = address.substringBeforeLast(':', "")
                        val port = address.substringAfterLast(':', "").toIntOrNull()
                        if (address.contains(':') && (host.isBlank() || port == null || port !in 1..65535)) {
                            discoveryMessage = "Enter a PC address, with an optional port from 1 to 65535."
                        } else {
                            chosen = if (port != null) DiscoveredHelper(host, port, "PC")
                                else DiscoveredHelper(address, HelperClient.DEFAULT_PORT, "PC")
                            discoveryMessage = null
                            code = ""
                            say("", false)
                        }
                    })
                    chosen?.takeIf { manualHost.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Selected: ${it.host}:${it.port}", color = TextC, fontSize = 13.sp)
                    }
                    SetupMessage(discoveryMessage, true)
                }
                if (chosen != null) {
                Spacer(Modifier.height(14.dp))
                SetupCard {
                    Text("Pair", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    // no digit-count/older-version/one-use explanation - the field
                    // and its error carry that when it matters. Validation is unchanged.
                    Text("Enter the code BingeMote is showing on your PC. No code on screen? Open BingeMote from the icon by the clock, type a name under Pair new phone, and tap Pair.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.filter(Char::isDigit).take(6)
                            // One less tap: a full 6th digit (older BingeMote) pairs right away; a 4-digit
                            // code goes with the Pair button below, since 4 is a prefix of 6.
                            if (code.length == 6 && !pairing && chosen != null) pair()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Code from your PC", color = Muted) },
                        // Number pad for the code (it opened the full keyboard).
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                        // Orange outline at rest, not only when focused: the field is the
                        // point of the screen once a PC is chosen.
                        colors = fieldColors(outline = Orange),
                    )
                    Spacer(Modifier.height(10.dp))
                    OrangeButton(if (pairing) "Pairing\u2026" else "Pair", enabled = !pairing, onClick = { pair() })
                    // Right under the button, not below Back where the keyboard hid it.
                    SetupMessage(message, messageIsError)
                }

                }

                // The confirmation lives below the form, not on its
                // own screen: paired, then the VLC hand-off under it.
                if (successVisible) {
                    Spacer(Modifier.height(14.dp))
                    SetupCard {
                        if (justPaired) {
                            Text("\u2713 Paired with $pairedName", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                        }
                        if (!autoSetup) {
                            Text("Update BingeMote on your PC, then pair again to set up VLC automatically.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                            OrangeButton("Pair again", onClick = { retryPair() })
                        } else when (autoState) {
                            "checking" -> {
                                if (!justPaired) { Text("Setting up VLC remote control\u2026", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)) }
                                Text("Your PC picked the password. Checking it once\u2026", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                            "ok" -> {
                                Text("VLC remote control is set up", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    when {
                                        autoPending ->
                                            "A VLC settings update is waiting on your PC - phone control starts when it's applied."
                                        autoNotOpen ->
                                            "VLC isn't open on the PC right now - the password is saved, and phone control starts when you open it."
                                        else ->
                                            "Your PC picked the password and saved it - nothing to type into VLC."
                                    },
                                    color = TextC, fontSize = 14.sp, lineHeight = 20.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                                OrangeButton("Done", onClick = { store.setupDone = true; onDone() })
                                SetupMessage(message, messageIsError)
                            }
                            "unreachable" -> {
                                Text("Couldn't check with your PC", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("BingeMote on the PC didn't answer. Make sure it's running, then try again.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(12.dp))
                                OrangeButton("Try again", onClick = { autoState = "checking"; checkRun++ })
                                SetupMessage(message, messageIsError)
                            }
                            "unknown" -> {
                                Text("Couldn't check with your PC", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("Your PC couldn't verify the VLC settings just now. Try again in a moment.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(12.dp))
                                OrangeButton("Try again", onClick = { autoState = "checking"; checkRun++ })
                                SetupMessage(message, messageIsError)
                            }
                            else -> {
                                Text("VLC refused the password", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("Pair again to get the password from BingeMote on your PC. If VLC is still running with old settings, close and reopen it.", color = TextC, fontSize = 14.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(12.dp))
                                OrangeButton("Pair again", onClick = { retryPair() })
                                SetupMessage(message, messageIsError)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
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
private fun SetupMessage(message: String?, isError: Boolean) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(message, color = if (isError) ErrorText else AccentText, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun OrangeButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // One primary button size app-wide: 48dp tall, 14dp corners (was 52/16 here, 46/14 in Settings).
    PressTile(
        onTap = { if (enabled) onClick() },
        background = if (enabled) OrangeGradient else CardGradient,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        corner = 14.dp,
        enabled = enabled,
    ) {
        Text(label, color = if (enabled) OnAccent else Dimmed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
