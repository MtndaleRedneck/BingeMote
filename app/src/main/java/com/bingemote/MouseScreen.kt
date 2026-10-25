package com.bingemote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mouse mode: the whole pad is a touchpad. Gestures copy the old app:
 * tap = left click, 2-finger tap = right click, 2-finger drag = scroll,
 * pinch = zoom (Ctrl+wheel on the PC), long-press + move = drag, tap = release drag.
 */
@Composable
fun MouseScreen(remote: RemoteState, startKeyboardOpen: Boolean = false, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keyboardOpen by remember { mutableStateOf(startKeyboardOpen) }
    var speedOpen by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(remote.store.mouseSpeed) }
    var helperOk by remember { mutableStateOf(true) }
    val view = LocalView.current

    // Published state, not plain prefs: rediscovery and re-pairing recompose and reconnect.
    val host = remote.liveHost
    val port = remote.livePort
    val token = remote.liveToken

    // The live mouse connection: frames while it is open, plain HTTP when it is not.
    val live = remember(host, port, token) { LiveWs(host, port, token) }
    LaunchedEffect(live) {
        HelperClient.live = live
        live.connect()
    }
    DisposableEffect(live) {
        onDispose {
            if (HelperClient.live === live) HelperClient.live = null
            live.close()
        }
    }

    // Backgrounded (call, screen off, home): close the live line and stop its reconnect
    // clock; foreground again: reconnect. `away` also re-keys the pad's gesture block so a
    // latched drag (released on background) doesn't eat the first tap after coming back.
    var away by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, live) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { away = true; live.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { away = false; live.resume() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Unbounded: a backed-up send must never shed a move/button (a dropped button-up
    // leaves the PC mouse pressed). The sender drains at link speed.
    val commands = remember { kotlinx.coroutines.channels.Channel<() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    LaunchedEffect(commands) {
        var failedInARow = 0
        for (command in commands) {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { command() }; helperOk = true
                failedInARow = 0
            } catch (e: Exception) {
                helperOk = false
                // Two dead sends in a row: the PC may sit at a new address - find it again.
                if (++failedInARow >= 2) remote.rediscover()
            }
        }
    }
    DisposableEffect(commands) { onDispose {
        commands.close()
        // Always release a held mouse button when leaving this screen.
        HelperClient.io.execute { try { HelperClient.button(host, port, token, "left", false) } catch (_: Exception) {} }
    } }
    fun fire(block: () -> Unit): Boolean {
        val queued = commands.trySend(block).isSuccess
        if (!queued) helperOk = false
        return queued
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Touchpad
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 190.dp, end = 40.dp)
                .pointerInput(host, port, token, away) {
                    var accX = 0f; var accY = 0f
                    var accScroll = 0f; var accZoom = 0f
                    val accLock = Any()
                    var dragLatched = false
                    var sender: Job? = null
                    var lpJob: Job? = null
                    // Delta-arrival signal: wakes the sender loop the moment new input lands.
                    val deltaSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
                    fun startSender() {
                        if (sender?.isActive == true) return
                        sender = scope.launch {
                            var landed: kotlinx.coroutines.CompletableDeferred<Unit>? = null
                            var lastSentAt = 0L
                            while (isActive) {
                                // Send-when-free: the next batch leaves the moment the previous one
                                // landed (paced to at most ~60/sec), so the rate follows the
                                // connection - fast on a healthy LAN, self-throttling when it's not.
                                val sendStarted = System.currentTimeMillis()
                                landed?.await()
                                val stalledMs = if (landed != null) System.currentTimeMillis() - sendStarted else 0L
                                val pace = lastSentAt + 16 - System.currentTimeMillis()
                                if (pace > 0) delay(pace)
                                // Moves ride the same channel as buttons and scrolls, so a
                                // drag can never start before its button-down reaches the PC.
                                val dx: Float; val dy: Float; val sc: Float; val zm: Float
                                synchronized(accLock) { dx = accX; dy = accY; accX = 0f; accY = 0f; sc = accScroll; zm = accZoom; accScroll = 0f; accZoom = 0f }
                                val (mx, my) = MouseSendPolicy.adjust(dx, dy, stalledMs)
                                if (sc != 0f) fire { HelperClient.scroll(host, port, token, sc) }
                                if (zm != 0f) fire { HelperClient.zoom(host, port, token, zm) }
                                if (mx != 0f || my != 0f) {
                                    val speed = remote.store.mouseSpeed
                                    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                                    landed = done
                                    if (!fire {
                                        try { HelperClient.move(host, port, token, mx * speed, my * speed) }
                                        finally { done.complete(Unit) }
                                    }) done.complete(Unit)
                                    lastSentAt = System.currentTimeMillis()
                                } else {
                                    // Wait for the next delta instead of napping a fixed 20 ms:
                                    // the nap quantized output into uneven short/long gaps
                                    // whenever input arrived slower than the pace (measured in
                                    // MouseCadenceTest). The 1 s cap only bounds idle waiting.
                                    kotlinx.coroutines.withTimeoutOrNull(1000) { deltaSignal.receive() }
                                }
                            }
                        }
                    }
                    try {
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        startSender()
                        val downTime = System.currentTimeMillis()
                        var twoFinger = false
                        var moved = false
                        var dragging = dragLatched // hold the button until the next tap
                        var pinchDist = 0f
                        var longPressFired = false
                        var sawSecond = false
                        val slop = viewConfiguration.touchSlop
                        var pendX = 0f; var pendY = 0f

                        // Long-press timer: 450ms stationary -> mouse button down (drag mode).
                        lpJob?.cancel()
                        lpJob = scope.launch {
                            delay(450)
                            if (!moved && !twoFinger && !dragLatched) {
                                longPressFired = true
                                dragging = true
                                dragLatched = true
                                tapHaptic(view, remote.store.haptics)
                                fire { HelperClient.button(host, port, token, "left", true) }
                            }
                        }

                        var secondDownAt = 0L
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                sawSecond = true
                                if (!twoFinger) {
                                    twoFinger = true
                                    lpJob?.cancel()
                                    val a = pressed[0].position; val b = pressed[1].position
                                    pinchDist = (a - b).getDistance()
                                    secondDownAt = System.currentTimeMillis()
                                } else if (twoFinger) {
                                    val a = pressed[0].position; val b = pressed[1].position
                                    val newDist = (a - b).getDistance()
                                    val moveA = pressed[0].positionChange()
                                    val moveB = pressed[1].positionChange()
                                    if (abs(newDist - pinchDist) > 12f) {
                                        // Pinch: zoom on the PC via Ctrl+wheel. Bundled like moves,
                                        // so a fast pinch can't queue a backlog that outlives the fingers.
                                        synchronized(accLock) { accZoom += (newDist - pinchDist) / 6f }
                                        deltaSignal.trySend(Unit)
                                        pinchDist = newDist
                                        moved = true
                                    } else {
                                        // Two fingers moving together: scroll.
                                        val avg = (moveA.y + moveB.y) / 2f
                                        // Bundled like moves: accumulate and let the sender flush. The
                                        // leftover fraction rides the next flush (or the lift flush), so
                                        // slow careful scrolling loses nothing.
                                        synchronized(accLock) { accScroll += -avg / 4f }
                                        deltaSignal.trySend(Unit)
                                        if (abs(avg) > 1f) moved = true
                                    }
                                    pressed.forEach { it.consume() }
                                }
                            } else if (pressed.size == 1 && !twoFinger) {
                                val ch = pressed[0]
                                val delta = ch.positionChange()
                                if (delta != Offset.Zero) {
                                    // A resting fingertip wobbles more than 2px; don't call it a move
                                    // (or send it) until the finger passes Android's touch slop from
                                    // where it first landed. Wobble before that never moves the pointer.
                                    if (!moved && (ch.position - first.position).getDistance() > slop) moved = true
                                    if (moved) {
                                        synchronized(accLock) { accX += pendX + delta.x; accY += pendY + delta.y }
                                        pendX = 0f; pendY = 0f
                                        // androidTest diagnosis only (dead in release).
                                        if (BuildConfig.DEBUG && MouseSendDebugHooks.traceEnabled) MouseSendDebugHooks.noteArrival()
                                        deltaSignal.trySend(Unit)
                                    } else { pendX += delta.x; pendY += delta.y }
                                    ch.consume()
                                }
                            }
                            if (pressed.isEmpty()) break
                        }
                        lpJob?.cancel()
                        lpJob = null
                        sender?.cancel()
                        sender = null
                        val lastX: Float; val lastY: Float; val lastSc: Float; val lastZm: Float
                        synchronized(accLock) { lastX = accX; lastY = accY; accX = 0f; accY = 0f; lastSc = accScroll; lastZm = accZoom; accScroll = 0f; accZoom = 0f }
                        // Lift flush: leftover scroll/zoom fractions still go out (slow scrolling loses no steps).
                        if (lastSc != 0f) fire { HelperClient.scroll(host, port, token, lastSc) }
                        if (lastZm != 0f) fire { HelperClient.zoom(host, port, token, lastZm) }
                        // The tail of a swipe used the ancient hardcoded 2.2x gain (the flick Ezzy feels at
// liftoff): flush at HIS speed like every other move.
if (lastX != 0f || lastY != 0f) fire { HelperClient.move(host, port, token, lastX * remote.store.mouseSpeed, lastY * remote.store.mouseSpeed) }

                        val duration = System.currentTimeMillis() - downTime
                        when {
                            dragging && !longPressFired && !moved && duration < 400 -> {
                                dragLatched = false
                                fire { HelperClient.button(host, port, token, "left", false) }
                            }
                            dragging -> { /* stays held until a later tap */ }
                            !moved && duration < 400 -> {
                                tapHaptic(view, remote.store.haptics)
                                if (sawSecond && System.currentTimeMillis() - secondDownAt < 350) {
                                    fire { HelperClient.button(host, port, token, "right", true); HelperClient.button(host, port, token, "right", false) }
                                } else if (!sawSecond) {
                                    fire { HelperClient.button(host, port, token, "left", true); HelperClient.button(host, port, token, "left", false) }
                                }
                            }
                        }
                    }
                    } finally {
                        // A re-keyed block cancels mid-gesture: kill the old sender and
                        // long-press timer so the new block starts its own.
                        lpJob?.cancel(); lpJob = null
                        sender?.cancel(); sender = null
                    }
                },
        )

        // The gesture cheat sheet, faint on the pad itself, centered in the free pad area
        // between the top buttons and the click buttons. The end padding keeps the lines
        // clear of the scroll strip.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 96.dp, bottom = 190.dp, end = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Tap for left click\nTwo-finger tap for right click\nTwo-finger drag to scroll\nPinch to zoom\nLong-press + move to drag\nTap to release drag",
                // Still quiet, but readable in a dark room (Dimmed was about 2.6:1 on the background).
                color = Muted.copy(alpha = 0.7f), fontSize = 15.sp, lineHeight = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        // Scroll strip on the right edge: it fills the pad between the top buttons and the
        // click buttons, with the same breathing space at both ends, on any screen size.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 84.dp, bottom = 116.dp, end = 10.dp)
                .width(26.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(13.dp))
                .background(Card)
                .pointerInput(host, port, token) {
                    var stripAcc = 0f
                    val stripLock = Any()
                    var stripSender: Job? = null
                    fun startStripSender() {
                        if (stripSender?.isActive == true) return
                        stripSender = scope.launch {
                            while (isActive) {
                                val sc: Float
                                synchronized(stripLock) { sc = stripAcc; stripAcc = 0f }
                                if (sc != 0f) {
                                    fire { HelperClient.scroll(host, port, token, sc) }
                                    delay(16)
                                } else delay(20)
                            }
                        }
                    }
                    awaitEachGesture {
                        awaitFirstDown()
                        startStripSender()
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            synchronized(stripLock) { stripAcc += -ch.positionChange().y / 3f }
                            ch.consume()
                        }
                        // Lift flush: the leftover fraction still goes out.
                        val tail: Float
                        synchronized(stripLock) { tail = stripAcc; stripAcc = 0f }
                        if (tail != 0f) fire { HelperClient.scroll(host, port, token, tail) }
                        stripSender?.cancel()
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(6.dp)
                    .fillMaxHeight(0.45f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Orange),
            )
        }

        // Top-left back chip, top-right gear (pointer speed) + keyboard buttons
        // Centered vertically, so the 40dp back button lines up with the 52dp round buttons.
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack, remote.store.haptics) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.weight(1f))
            PressTile(
                onTap = { speedOpen = !speedOpen },
                background = OrangeGradient,
                modifier = Modifier.size(52.dp),
                corner = 26.dp,
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Pointer speed", tint = androidx.compose.ui.graphics.Color(0xFF1B1B1E), modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            PressTile(
                onTap = { keyboardOpen = true; speedOpen = false },
                background = OrangeGradient,
                modifier = Modifier.size(52.dp),
                corner = 26.dp,
            ) {
                KeyboardIcon(androidx.compose.ui.graphics.Color(0xFF1B1B1E), 26.dp)
            }
        }

        // The gear's pointer-speed panel: - / value / +, effective at once. Tap outside closes.
        if (speedOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (waitForUpOrCancellation() != null) speedOpen = false
                        }
                    },
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    // Sits below the red warning when it shows, otherwise right under the
                    // top buttons; centered keeps it clear of the scroll strip.
                    .padding(top = if (helperOk) 84.dp else 150.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Card)
                    // taps inside the panel don't close it
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation() } }
                    .padding(14.dp),
            ) {
                Text("Pointer speed", color = TextC, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedStep("\u2212", remote.store.haptics) { speed = (kotlin.math.round((speed - 0.1f) * 10) / 10).coerceAtLeast(0.5f); remote.store.mouseSpeed = speed }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "%.1fx".format(speed),
                        color = TextC, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.width(14.dp))
                    SpeedStep("+", remote.store.haptics) { speed = (kotlin.math.round((speed + 0.1f) * 10) / 10).coerceAtMost(2.0f); remote.store.mouseSpeed = speed }
                }
            }
        }

        if (!helperOk) {
            Text(
                "Can't reach BingeMote on the PC.\nIs it open?",
                color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, end = 40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Card)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Bottom: click buttons (the gestures live on the pad itself now)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                MouseButton("Left click", "left", remote.store.haptics, Modifier.weight(1f).height(84.dp)) { b, d -> fire { HelperClient.button(host, port, token, b, d) } }
                Spacer(Modifier.width(12.dp))
                MouseButton("Right click", "right", remote.store.haptics, Modifier.weight(1f).height(84.dp)) { b, d -> fire { HelperClient.button(host, port, token, b, d) } }
            }
        }
    }

    BackHandler { onBack() }

    if (keyboardOpen) {
        KeyboardSheet(
            onClose = { keyboardOpen = false },
            onType = { text -> fire { HelperClient.typeText(host, port, token, text) } },
            onKey = { key -> fire { HelperClient.key(host, port, token, key) } },
            hapticsOn = remote.store.haptics,
        )
    }
}

/** One - / + step of the pointer-speed stepper. */
@Composable
private fun SpeedStep(glyph: String, hapticsOn: Boolean, onTap: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .size(52.dp, 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HeaderBtn)
            // Ripple + tick like every other button (it had neither).
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                tapHaptic(view, hapticsOn)
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Orange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** A click button: press = button down on the PC, release = button up (so real drags work). */
@Composable
private fun MouseButton(
    label: String,
    button: String,
    hapticsOn: Boolean,
    modifier: Modifier = Modifier,
    send: (String, Boolean) -> Unit,
) {
    val view = LocalView.current
    // Same press feedback as every other remote button (PressTile): sink + lower shadow.
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "sink")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (pressed) 2.dp else 6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(CardGradient)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view, hapticsOn)
                    pressed = true
                    send(button, true)
                    try { waitForUpOrCancel() } finally { pressed = false; send(button, false) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return
    }
}

/** Type text and send named keys to the PC. */
@Composable
private fun KeyboardSheet(onClose: () -> Unit, onType: (String) -> Unit, onKey: (String) -> Unit, hapticsOn: Boolean) {
    val view = LocalView.current
    var text by remember { mutableStateOf("") }
    // Tapping anywhere outside dismisses, so there is no Done button.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        // Use the real screen width, so 66% means 66% of the screen (the platform
        // default would cap the dialog near 320dp and squeeze the key row off).
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 66% of the screen width (Ezzy's pick over 58%): roomier, not wide.
        // At large text the key row needs real room: an S22 Ultra is 384dp wide and
        // 66% of it clipped "Space", so a 290dp floor kicks in above 1.2x font scale.
        val wideText = LocalDensity.current.fontScale >= 1.2f
        androidx.compose.material3.Surface(
            modifier = Modifier.widthIn(min = if (wideText) 290.dp else 0.dp).fillMaxWidth(0.66f),
            shape = RoundedCornerShape(28.dp),
            color = Card2,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)) {
                Text("Keyboard", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        // Diff against the common prefix: autocorrect and suggestion taps replace
                        // whole words, and a plain tail-add/tail-delete check loses them. Backspaces
                        // and letters ride the same ordered channel, so they land in this order.
                        val keep = text.commonPrefixWith(new).length
                        repeat(text.length - keep) { onKey("backspace") }
                        if (new.length > keep) onType(new.substring(keep))
                        text = new
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type here", color = Muted) },
                    // Single-line with a Send action: the Enter key submits (real Enter on the PC)
                    // instead of typing a line break.
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onKey("enter")
                        // The PC-side box just submitted: empty the field. Assigning the state
                        // emits nothing - no pretend backspaces that would eat PC-side text.
                        text = ""
                    }),
                )
                Spacer(Modifier.height(10.dp))
                // One row of four, Ezzy's approved render: plain clickable Boxes, so no
                // TextButton minimum width or default padding can squeeze a key into wrapping.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("enter" to "Enter", "backspace" to "Backspace", "escape" to "Esc", "space" to "Space").forEach { (k, label) ->
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { tapHaptic(view, hapticsOn); onKey(k) }
                                // 2dp, not 4: the real 1.3x row measured wider than the spec font
                                // said, and 4dp clipped the last key.
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label, color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, softWrap = false,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Cross arrow cluster: chunky vector arrows (Ezzy's pick), same
                // size and positions as before - only the artwork changed.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { tapHaptic(view, hapticsOn); onKey("up") }) { ArrowIcon(ArrowDir.Up, Orange) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { tapHaptic(view, hapticsOn); onKey("left") }) { ArrowIcon(ArrowDir.Left, Orange) }
                    Spacer(Modifier.width(40.dp))
                    TextButton(onClick = { tapHaptic(view, hapticsOn); onKey("right") }) { ArrowIcon(ArrowDir.Right, Orange) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { tapHaptic(view, hapticsOn); onKey("down") }) { ArrowIcon(ArrowDir.Down, Orange) }
                }
            }
        }
    }
}

/** What a send does with motion that piled up while the previous send was in
 * flight. 1.9 restores the 1.6 answer: ALL of it, in one batch. The 1.7-1.8
 * stall shrink (cut to 25% after a >250ms send) visibly ate movement on a busy
 * link - the jerky-mouse report. Kept as one tiny function so the policy has a
 * regression test (MouseSendPolicyTest). */
internal object MouseSendPolicy {
    fun adjust(dx: Float, dy: Float, stalledMs: Long): Pair<Float, Float> = dx to dy
}
