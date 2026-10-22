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
import androidx.compose.ui.platform.LocalView
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

    val host = remote.store.host
    val port = remote.store.helperPort
    val token = remote.store.helperToken

    val commands = remember { kotlinx.coroutines.channels.Channel<() -> Unit>(256) }
    LaunchedEffect(commands) {
        for (command in commands) {
            try { kotlinx.coroutines.withContext(Dispatchers.IO) { command() }; helperOk = true }
            catch (e: Exception) { helperOk = false }
        }
    }
    DisposableEffect(commands) { onDispose {
        commands.close()
        // Always release a held mouse button when leaving this screen.
        java.util.concurrent.Executors.newSingleThreadExecutor().also { executor ->
            executor.execute { try { HelperClient.button(host, port, token, "left", false) } catch (_: Exception) {} }
            executor.shutdown()
        }
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
                .pointerInput(host, port, token) {
                    var accX = 0f; var accY = 0f
                    val accLock = Any()
                    var dragLatched = false
                    var sender: Job? = null
                    fun startSender() {
                        if (sender?.isActive == true) return
                        sender = scope.launch {
                            var landed: kotlinx.coroutines.CompletableDeferred<Unit>? = null
                            var lastSentAt = 0L
                            while (isActive) {
                                // Send-when-free: the next batch leaves the moment the previous one
                                // landed (paced to at most ~60/sec), so the rate follows the
                                // connection - fast on a healthy LAN, self-throttling when it's not.
                                landed?.await()
                                val pace = lastSentAt + 16 - System.currentTimeMillis()
                                if (pace > 0) delay(pace)
                                // Moves ride the same channel as buttons and scrolls, so a
                                // drag can never start before its button-down reaches the PC.
                                val dx: Float; val dy: Float
                                synchronized(accLock) { dx = accX; dy = accY; accX = 0f; accY = 0f }
                                if (dx != 0f || dy != 0f) {
                                    val speed = remote.store.mouseSpeed
                                    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                                    landed = done
                                    if (!fire {
                                        try { HelperClient.move(host, port, token, dx * speed, dy * speed) }
                                        finally { done.complete(Unit) }
                                    }) done.complete(Unit)
                                    lastSentAt = System.currentTimeMillis()
                                } else {
                                    delay(20)
                                }
                            }
                        }
                    }
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        startSender()
                        val downTime = System.currentTimeMillis()
                        var twoFinger = false
                        var moved = false
                        var dragging = dragLatched // hold the button until the next tap
                        var pinchDist = 0f
                        var scrollAcc = 0f
                        var longPressFired = false
                        var sawSecond = false

                        // Long-press timer: 450ms stationary -> mouse button down (drag mode).
                        val lpJob = scope.launch {
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
                                    lpJob.cancel()
                                    val a = pressed[0].position; val b = pressed[1].position
                                    pinchDist = (a - b).getDistance()
                                    secondDownAt = System.currentTimeMillis()
                                } else if (twoFinger) {
                                    val a = pressed[0].position; val b = pressed[1].position
                                    val newDist = (a - b).getDistance()
                                    val moveA = pressed[0].positionChange()
                                    val moveB = pressed[1].positionChange()
                                    if (abs(newDist - pinchDist) > 12f) {
                                        // Pinch: zoom on the PC via Ctrl+wheel.
                                        fire { HelperClient.zoom(host, port, token, (newDist - pinchDist) / 6f) }
                                        pinchDist = newDist
                                        moved = true
                                    } else {
                                        // Two fingers moving together: scroll.
                                        val avg = (moveA.y + moveB.y) / 2f
                                        scrollAcc += avg
                                        if (abs(scrollAcc) > 18f) {
                                            val amount = scrollAcc
                                            scrollAcc = 0f
                                            fire { HelperClient.scroll(host, port, token, -amount / 4f) }
                                        }
                                        if (abs(avg) > 1f) moved = true
                                    }
                                    pressed.forEach { it.consume() }
                                }
                            } else if (pressed.size == 1 && !twoFinger) {
                                val ch = pressed[0]
                                val delta = ch.positionChange()
                                if (delta != Offset.Zero) {
                                    if (abs(delta.x) + abs(delta.y) > 2f) moved = true
                                    synchronized(accLock) { accX += delta.x; accY += delta.y }
                                    ch.consume()
                                }
                            }
                            if (pressed.isEmpty()) break
                        }
                        lpJob.cancel()
                        sender?.cancel()
                        val lastX: Float; val lastY: Float
                        synchronized(accLock) { lastX = accX; lastY = accY; accX = 0f; accY = 0f }
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
                    awaitEachGesture {
                        awaitFirstDown()
                        var acc = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            acc += ch.positionChange().y
                            if (abs(acc) > 14f) {
                                val amount = acc
                                acc = 0f
                                fire { HelperClient.scroll(host, port, token, -amount / 3f) }
                            }
                            ch.consume()
                        }
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
private fun KeyboardSheet(onClose: () -> Unit, onType: (String) -> Unit, onKey: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // Tapping anywhere outside dismisses, so there is no Done button.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        // Use the real screen width, so 66% means 66% of the screen (the platform
        // default would cap the dialog near 320dp and squeeze the key row off).
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            // 66% of the screen width (Ezzy's pick over 58%): roomier, not wide.
            modifier = Modifier.fillMaxWidth(0.66f),
            shape = RoundedCornerShape(28.dp),
            color = Card2,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)) {
                Text("Keyboard", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        // Type the difference straight into the PC.
                        if (new.length > text.length && new.startsWith(text)) {
                            onType(new.substring(text.length))
                        } else if (new.length < text.length) {
                            repeat(text.length - new.length) { onKey("backspace") }
                        }
                        text = new
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type here", color = Muted) },
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
                                .clickable(role = Role.Button) { onKey(k) }
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
                    TextButton(onClick = { onKey("up") }) { ArrowIcon(ArrowDir.Up, Orange) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { onKey("left") }) { ArrowIcon(ArrowDir.Left, Orange) }
                    Spacer(Modifier.width(40.dp))
                    TextButton(onClick = { onKey("right") }) { ArrowIcon(ArrowDir.Right, Orange) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { onKey("down") }) { ArrowIcon(ArrowDir.Down, Orange) }
                }
            }
        }
    }
}
