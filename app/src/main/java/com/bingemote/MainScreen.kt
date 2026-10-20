package com.bingemote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    remote: RemoteState,
    onMouse: () -> Unit,
    onMovies: () -> Unit,
    onSettings: () -> Unit,
) {
    val s = remote.status
    var subtitlesOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar {
                HeaderIconButton(onMouse, remote.store.haptics) { CursorIcon(Orange) }
                HeaderIconButton(onSettings, remote.store.haptics) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings", tint = Orange,
                    )
                }
            }

            // Now playing
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = Orange, spotColor = Orange)
                    .clip(RoundedCornerShape(20.dp))
                    .background(OrangeGradient)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column {
                    Text(
                        s?.title?.ifBlank { "Nothing playing" } ?: "Nothing playing",
                        color = Color(0xFF1B1B1E), fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    Text(
                        when (s?.state) {
                            "playing" -> "Playing"
                            "paused" -> "Paused"
                            else -> "Stopped"
                        },
                        color = Color(0xFF3D2A12), fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SeekBlock(remote)
            Spacer(Modifier.height(18.dp))
            VolumeRow(remote)
            Spacer(Modifier.height(18.dp))
            SkipRow(remote)
            Spacer(Modifier.height(12.dp))
            SecondarySkipRow(remote)
            Spacer(Modifier.height(8.dp))
            Text(
                "Hold a skip button to keep skipping",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.weight(1f))
            BottomRow(remote, onSubtitles = { subtitlesOpen = true }, onMovies = onMovies)
            Spacer(Modifier.height(14.dp))
        }

        if (subtitlesOpen) {
            SubtitleSheet(remote, onClose = { subtitlesOpen = false })
        }
        if (remote.unreachable) {
            ErrorCard(onRetry = { remote.retry() })
        }
    }
}

/** Progress bar with hold-to-unlock seeking. Locked by default; hold 1-2s to unlock, relocks after ~5s idle. */
@Composable
private fun SeekBlock(remote: RemoteState) {
    val s = remote.status
    val length = s?.lengthSec ?: 0
    val position = if (length > 0) (s?.timeSec ?: 0).toFloat() / length else 0f

    var unlocked by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var idleStamp by remember { mutableStateOf(0L) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Relock about five seconds after the last touch.
    LaunchedEffect(unlocked, idleStamp) {
        if (unlocked) {
            delay(5000)
            unlocked = false
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(s?.timeSec ?: 0), color = Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("-${formatTime(s?.remainingSec ?: 0)}", color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(length, unlocked) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var holdJob: Job? = null
                        if (!unlocked) {
                            holdJob = scope.launch {
                                delay(850)
                                tapHaptic(view, remote.store.haptics)
                                unlocked = true
                                idleStamp = System.currentTimeMillis()
                            }
                        }
                        var moved = false
                        var dragging = false
                        var up = false
                        while (!up) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val dx = change.positionChange().x
                                if (!moved && kotlin.math.abs(change.position.x - down.position.x) > 14f) moved = true
                                if (unlocked && (moved || dragging)) {
                                    dragging = true
                                    holdJob?.cancel()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    dragPos = ((dragPos ?: position) + dx / w).coerceIn(0f, 1f)
                                    idleStamp = System.currentTimeMillis()
                                }
                                change.consume()
                            } else {
                                up = true
                            }
                        }
                        holdJob?.cancel()
                        if (dragging) {
                            val p = dragPos
                            dragPos = null
                            if (p != null && length > 0) {
                                tapHaptic(view, remote.store.haptics)
                                remote.seekTo((p * length).toLong())
                            }
                        }
                        if (unlocked) idleStamp = System.currentTimeMillis()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(TrackGray))
            // Fill
            val shown = dragPos ?: position
            Box(
                Modifier
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (unlocked) OrangeLight else Orange),
            )
            // Thumb
            Box(Modifier.fillMaxWidth()) {
                val thumbSize = if (unlocked) 20.dp else 16.dp
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = ((-10).dp))
                        .graphicsLayoutX(shown, thumbSize)
                        .size(thumbSize)
                        .then(
                            if (unlocked) Modifier.shadow(8.dp, CircleShape, ambientColor = Orange, spotColor = Orange)
                            else Modifier
                        )
                        .clip(CircleShape)
                        .background(if (unlocked) OrangeLight else Orange),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (unlocked) "Unlocked - locks on its own after a few seconds" else "Hold the bar to unlock seeking",
                color = if (unlocked) Orange else Muted, fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("${formatTime(length)} total", color = Muted, fontSize = 12.sp)
        }
    }
}

/** Places the thumb along the bar by fraction, centered on the fill's end. */
private fun Modifier.graphicsLayoutX(fraction: Float, thumbSize: androidx.compose.ui.unit.Dp) =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val w = constraints.maxWidth
        layout(placeable.width, placeable.height) {
            val x = (fraction.coerceIn(0f, 1f) * w - placeable.width / 2).toInt().coerceIn(0, (w - placeable.width).coerceAtLeast(0))
            placeable.placeRelative(x, 0)
        }
    })

@Composable
private fun VolumeRow(remote: RemoteState) {
    val s = remote.status
    val muted = remote.muted
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressTile(
            onTap = { remote.volumeStep(false) },
            onHoldRepeat = { remote.volumeStep(false) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(84.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SpeakerIcon(TextC, waves = 1)
                Spacer(Modifier.height(6.dp))
                TileLabel("Vol down", TextC)
            }
        }
        Spacer(Modifier.width(12.dp))
        // Percent readout: tap toggles mute. Muted: dimmed 0% + MUTED, thin red strike corner to corner.
        PressTile(
            onTap = { remote.toggleMute() },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.width(96.dp).height(84.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (muted) "0%" else "${remote.winVolume ?: (s?.volumePct ?: 0)}%",
                        color = if (muted) Dimmed else TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (muted) "MUTED" else "VOL",
                        color = if (muted) Dimmed else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (muted) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawLine(
                            ErrorRed,
                            Offset(size.width * 0.12f, size.height * 0.88f),
                            Offset(size.width * 0.88f, size.height * 0.12f),
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        PressTile(
            onTap = { remote.volumeStep(true) },
            onHoldRepeat = { remote.volumeStep(true) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(84.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SpeakerIcon(TextC, waves = 2)
                Spacer(Modifier.height(6.dp))
                TileLabel("Vol up", TextC)
            }
        }
    }
}

@Composable
private fun SkipRow(remote: RemoteState) {
    val label = formatSkip(remote.store.skipMainSec)
    val playing = remote.status?.playing == true
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressTile(
            onTap = { remote.skip(-remote.store.skipMainSec) },
            onHoldRepeat = { remote.skip(-remote.store.skipMainSec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(108.dp),
        ) { pressed ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SkipIcon(forward = false, label = label, pressed = pressed, tint = TextC)
                Spacer(Modifier.height(8.dp))
                TileLabel("Back $label", TextC)
            }
        }
        Spacer(Modifier.width(12.dp))
        PlayPauseButton(remote, playing)
        Spacer(Modifier.width(12.dp))
        PressTile(
            onTap = { remote.skip(remote.store.skipMainSec) },
            onHoldRepeat = { remote.skip(remote.store.skipMainSec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(108.dp),
        ) { pressed ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SkipIcon(forward = true, label = label, pressed = pressed, tint = TextC)
                Spacer(Modifier.height(8.dp))
                TileLabel("Fwd $label", TextC)
            }
        }
    }
}

@Composable
private fun PlayPauseButton(remote: RemoteState, playing: Boolean) {
    PressTile(
        onTap = { remote.togglePause() },
        hapticsOn = remote.store.haptics,
        background = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Card2, Card)),
        modifier = Modifier.size(108.dp),
        corner = 54.dp,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(16.dp, CircleShape, ambientColor = Orange, spotColor = Orange)
                .clip(CircleShape)
                .background(Card)
                .then(
                    Modifier.padding(4.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawCircle(Orange, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            }
            if (playing) PauseIcon(Orange, 40.dp) else PlayIcon(Orange, 40.dp)
        }
    }
}

@Composable
private fun SecondarySkipRow(remote: RemoteState) {
    val label = formatSkip(remote.store.skipSecondarySec)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PressTile(
            onTap = { remote.skip(-remote.store.skipSecondarySec) },
            onHoldRepeat = { remote.skip(-remote.store.skipSecondarySec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(62.dp),
        ) { pressed ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SkipIcon(forward = false, label = label, pressed = pressed, tint = Orange, size = 22.dp)
                Spacer(Modifier.height(2.dp))
                TileLabel("Back $label", Orange, size = 13)
            }
        }
        Spacer(Modifier.width(12.dp))
        PressTile(
            onTap = { remote.skip(remote.store.skipSecondarySec) },
            onHoldRepeat = { remote.skip(remote.store.skipSecondarySec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height(62.dp),
        ) { pressed ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SkipIcon(forward = true, label = label, pressed = pressed, tint = Orange, size = 22.dp)
                Spacer(Modifier.height(2.dp))
                TileLabel("Fwd $label", Orange, size = 13)
            }
        }
    }
}

@Composable
private fun BottomRow(remote: RemoteState, onSubtitles: () -> Unit, onMovies: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PressTile(onTap = { remote.toggleFullscreen() }, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height(80.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FullscreenIcon(TextC)
                Spacer(Modifier.height(6.dp))
                TileLabel("Fullscreen", TextC, size = 13)
            }
        }
        Spacer(Modifier.width(12.dp))
        PressTile(onTap = onSubtitles, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height(80.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SubtitleIcon(TextC)
                Spacer(Modifier.height(6.dp))
                TileLabel("Subtitles", TextC, size = 13)
            }
        }
        Spacer(Modifier.width(12.dp))
        PressTile(onTap = onMovies, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height(80.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FolderIcon(TextC)
                Spacer(Modifier.height(6.dp))
                TileLabel("Movies", TextC, size = 13)
            }
        }
    }
}

/** Bottom sheet with the movie's subtitle tracks. */
@Composable
private fun SubtitleSheet(remote: RemoteState, onClose: () -> Unit) {
    val tracks = remote.status?.subtitleTracks ?: emptyList()
    val picked = remote.pickedSubtitle
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3101013))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) onClose()
                }
            },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Card)
                // taps inside the sheet don't close it
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation() } }
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("Subtitles", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            SubtitleRow("Off", picked == -1 || (picked == null && tracks.none { it.selected })) {
                remote.pickSubtitle(-1); onClose()
            }
            tracks.forEach { t ->
                val checked = if (picked != null) picked == t.id else t.selected
                SubtitleRow(t.name, checked) {
                    remote.pickSubtitle(t.id); onClose()
                }
            }
            if (tracks.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("No subtitle tracks in this video.", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SubtitleRow(name: String, checked: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view, true)
                    if (waitForUpOrCancellation() != null) onClick()
                }
            }
            .padding(vertical = 13.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            androidx.compose.material3.Icon(
                Icons.Default.Check,
                contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(26.dp))
        }
        Text(name, color = if (checked) Orange else TextC, fontSize = 16.sp, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
    }
}

/** The connection-lost card. */
@Composable
private fun ErrorCard(onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3101013)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Card)
                .padding(18.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ErrorRed)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Can't reach your PC", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "The PC may be off, asleep, or the helper program isn't running.",
                color = TextC, fontSize = 14.sp,
            )
            Spacer(Modifier.height(14.dp))
            PressTile(onTap = onRetry, background = OrangeGradient, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Retry", color = Color(0xFF1B1B1E), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "The app reconnects by itself when you open it.",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
