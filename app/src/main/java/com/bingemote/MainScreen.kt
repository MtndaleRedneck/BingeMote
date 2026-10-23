package com.bingemote

import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    remote: RemoteState,
    onMouse: () -> Unit,
    onMovies: () -> Unit,
    onSettings: () -> Unit,
    startSubtitlesOpen: Boolean = false,
) {
    val s = remote.status
    val flashAll = remote.store.buttonFlash == 1
    var subtitlesOpen by remember { mutableStateOf(startSubtitlesOpen) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                pill = { StatusPill(connected = !remote.unreachable) },
                settings = {
                    HeaderIconButton(onSettings, remote.store.haptics, startPad = 6.dp) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings", tint = Orange,
                        )
                    }
                },
            )

            // Ezzy's responsive layout, measured not estimated (Tab's ruling):
            // a probe pass measures the true content height, then the main pass
            // places the rows with the REAL leftover split into equal gaps. No
            // fitted constants: equality holds on any profile, font or nav mode,
            // and normal placement (no draw-layer scaling) means nothing can be
            // painted out of place. Gaps are body-edge to body-edge; the card
            // and play halos are decorative glow outside the body and stay out
            // of the accounting (agreed with Tab).
            SubcomposeLayout(Modifier.fillMaxWidth().weight(1f)) { constraints ->
                val d = density
                val fs = fontScale
                val availWdp = constraints.maxWidth / d
                val availHdp = constraints.maxHeight / d
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                // Probe pass: the true non-gap content height in dp (seed margins
                // only affect width; the +14dp is the fixed bottom margin).
                val probe = subcompose("probe") {
                    HomeContent(remote, flashAll, 12f, onSubtitles = { subtitlesOpen = true }, onMovies = onMovies, onMouse = onMouse)
                }.map { it.measure(loose) }
                val gapCount = (probe.size - 1).coerceAtLeast(1)
                val contentDp = probe.sumOf { it.height } / d + 14f
                // Ezzy's responsive rules, verified 8:07 PM in his own words: a
                // smaller phone shrinks EVERYTHING to scale; a different aspect
                // ratio re-spaces the rows, content size untouched; nothing ever
                // grows past how it renders on his screen. One uniform factor
                // from two axes (Tab technical agreement): width vs his INFERRED
                // 356dp reference width (not confirmed device state); height only
                // on REAL overflow of the measured content - the WHOLE home then
                // shrinks uniformly, text included, never tile-only. Applied as a
                // density override on the dp axis only: sp -> px is
                // sp * fontScale * density, so scaling density moves text and
                // tiles by the same factor; scaling fontScale too would square
                // it for text. Exact no-op at uniform == 1f.
                val uniform = minOf(1f, availWdp / 356f, availHdp / contentDp).coerceAtLeast(0.75f)
                // Ezzy's drawing ("The goal. All equal."): side margins and
                // inter-tile gaps match the vertical gaps, in the content's own
                // coordinate space. The placed gaps below come out to exactly
                // this many px. Capped so taller phones grow only the vertical
                // gaps (his re-spacing rule) and tiny or overflowing screens
                // keep usable margins.
                val rawShare = (availHdp / uniform - contentDp) / gapCount
                val shareContent = rawShare.coerceIn(4f, 16f)
                val scaled = Density(d * uniform, fs)
                val placeables = subcompose("main") {
                    CompositionLocalProvider(LocalDensity provides scaled) {
                        HomeContent(remote, flashAll, shareContent, onSubtitles = { subtitlesOpen = true }, onMovies = onMovies, onMouse = onMouse)
                    }
                }.map { it.measure(loose) }
                val bottomPx = 14f * d * uniform
                val leftover = (constraints.maxHeight - placeables.sumOf { it.height } - bottomPx).coerceAtLeast(0f)
                val realGap = leftover / gapCount
                if (BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "BMLayout",
                        "fontScale=$fs availW=$availWdp availH=$availHdp contentDp=$contentDp " +
                            "uniform=$uniform share=$shareContent rawShare=$rawShare " +
                            "capLow=${rawShare < 4f} capHigh=${rawShare > 16f} realGapPx=$realGap helperDown=${remote.helperDown}",
                    )
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    var y = 0f
                    placeables.forEachIndexed { i, placeable ->
                        placeable.place(0, y.roundToInt())
                        y += placeable.height + if (i < placeables.lastIndex) realGap else bottomPx
                    }
                }
            }
        }

        if (subtitlesOpen) {
            SubtitleSheet(remote, onClose = { subtitlesOpen = false })
        }
        // Back closes the subtitle sheet first, like every other sheet.
        BackHandler(enabled = subtitlesOpen) { subtitlesOpen = false }
    }
}


/** The home rows, top to bottom. They carry NO vertical spacing of their own:
 * the enclosing measured layout places them with equal gaps (Ezzy's drawing:
 * "The goal. All equal."). [marginDp] is the shared horizontal share - side
 * margins and inter-tile gaps, the same size as the vertical gaps. The seek
 * block sits above or below the skip rows per the Settings toggle (default below). */
@Composable
private fun HomeContent(
    remote: RemoteState,
    flashAll: Boolean,
    marginDp: Float,
    onSubtitles: () -> Unit,
    onMovies: () -> Unit,
    onMouse: () -> Unit,
) {
    val s = remote.status
    // Now playing
    // When the PC is unreachable the card itself says so; no overlay, no Retry.
    // VLC closed but the PC program answers: say that instead (the helper starts VLC from Movies).
    val vlcOut = remote.vlcClosed && !remote.expectingVlc
    val lost = remote.unreachable && !remote.expectingVlc && !remote.vlcClosed
    Box(
        Modifier
            .padding(horizontal = marginDp.dp)
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = Orange, spotColor = Orange)
            .clip(RoundedCornerShape(20.dp))
            .background(OrangeGradient)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                if (lost) "Can't reach your PC" else if (vlcOut) "VLC isn't open"
                else s?.title?.let { displayName(it).ifBlank { "Nothing playing" } } ?: "Nothing playing",
                color = Color(0xFF1B1B1E), fontSize = 21.sp, fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Text(
                if (lost) "Trying to reconnect"
                else if (vlcOut) "Pick a film in Movies and it starts by itself"
                else when (s?.state) {
                    "playing" -> "Playing"
                    "paused" -> "Paused"
                    else -> "Stopped"
                },
                color = Color(0xFF3D2A12), fontSize = 14.sp,
            )
        }
    }

    val seekDrag = remember { SeekDrag() }
    SeekBlock(remote, marginDp, seekDrag)
    VolumeRow(remote, flashAll, marginDp)
    if (remote.helperDown) {
        Column {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .padding(horizontal = marginDp.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("Can't reach BingeMote on the PC", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mouse, keyboard and PC volume are off for now, so the volume buttons use VLC instead. To fix it, open BingeMote on the PC and tap \u201CThis is my home Wi-Fi\u201D.",
                    color = TextC, fontSize = 13.sp,
                )
            }
        }
    }
    SkipRow(remote, flashAll, marginDp, seekDrag = seekDrag)
    SecondarySkipRow(remote, marginDp)
    BottomRow(remote, flashAll, marginDp, onSubtitles = onSubtitles, onMovies = onMovies, onMouse = onMouse)
}

/** The connection state, top right: a quiet dot + word. An indicator only - not tappable. */
@Composable
private fun StatusPill(connected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(HeaderBtn)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (connected) Orange else ErrorRed),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected) "Connected" else "Disconnected",
            color = TextC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Live seek-drag facts shared between the bar (the gesture owner) and the
 * play/pause circle (the cancel target while a drag is active). */
/** eventTime of the last raw ACTION_CANCEL entering the app; 0 = none since
 * the current gesture started. Written by MainActivity.dispatchTouchEvent. */
object RawTouch { var cancelAt = 0L }

private class SeekDrag {
    var active by mutableStateOf(false)
    var inCircle by mutableStateOf(false)
    var proximity by mutableStateOf(0f)
    var circle by mutableStateOf<Rect?>(null)
    var barOrigin by mutableStateOf(Offset.Zero)
}

/** Buzz once per entry into the cancel circle, and track how close the finger is. */
private fun SeekDrag.trackCircle(finger: Offset, rampPx: Float, view: View, haptics: Boolean) {
    val cb = circle ?: return
    val inside = cb.contains(finger)
    if (inside != inCircle) {
        if (inside) tapHaptic(view, haptics)
        inCircle = inside
    }
    val dx = maxOf(cb.left - finger.x, 0f, finger.x - cb.right)
    val dy = maxOf(cb.top - finger.y, 0f, finger.y - cb.bottom)
    proximity = (1f - kotlin.math.hypot(dx, dy) / rampPx).coerceIn(0f, 1f)
}

/** Progress bar with hold-to-unlock seeking. Locked by default; hold 500ms to
 * unlock, relocks about 5s after the finger leaves. During a drag the play/pause
 * circle becomes the cancel target: release inside it keeps the place, release
 * anywhere else seeks, and the thumb plus times track the finger's absolute x
 * the whole time - inside the circle too. */
@Composable
private fun SeekBlock(remote: RemoteState, marginDp: Float, drag: SeekDrag) {
    val s = remote.status
    val length = s?.lengthSec ?: 0
    val position = if (length > 0) (s?.timeSec ?: 0).toFloat() / length else 0f

    var unlocked by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var idleStamp by remember { mutableStateOf(0L) }
    // A press holds the unlock: the idle relock counts only once the finger is
    // gone, so a held-but-stationary drag can never lose the gesture (or drop
    // the cancel circle) mid-press.
    var pressing by remember { mutableStateOf(false) }
    // Read through a stable reference so unlocking mid-press doesn't restart the gesture
    // (a restarted handler drops the finger: hold-then-slide needed a second touch).
    val unlockedNow by rememberUpdatedState(unlocked)
    // Where the finger was lifted, held until VLC's poll confirms it (kills the ~1s jump-back).
    var pendingSeek by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val seekDensity = LocalDensity.current
    val rampPx = with(seekDensity) { 96.dp.toPx() }

    // Relock about five seconds after the last touch, never mid-press.
    LaunchedEffect(unlocked, idleStamp, pressing) {
        if (unlocked && !pressing) {
            delay(5000)
            unlocked = false
        }
    }

    // Tab's call (and the right one): kill the invisible font padding in the
    // source instead of draw offsets, so the seek block's visible edges are its
    // glyphs and the five row gaps measure equal.
    val tight = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    Column(Modifier.padding(horizontal = marginDp.dp)) {
        // Ezzy's gaps are measured glyph-to-glyph, but digits only fill their
        // cap height: a natural line box reserves ascent/descent room the time
        // row never inks (~11px at font 1.0), which made the gap above this
        // row read too big on his ruler. Measure the digit ink at runtime and
        // report this row's height as that ink, centered, so the block's outer
        // gaps measure like every other row's. The glyphs are untouched; cap
        // height is identical across font weights, so a plain Paint suffices.
        val digitInkPx = remember(seekDensity) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.textSize = with(seekDensity) { 14.sp.toPx() }
            val bounds = android.graphics.Rect()
            paint.getTextBounds("0", 0, 1, bounds)
            bounds.height()
        }
        Row(
            Modifier.fillMaxWidth().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (digitInkPx <= 0 || digitInkPx >= placeable.height) {
                    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                val reserve = placeable.height - digitInkPx
                layout(placeable.width, placeable.height - reserve) {
                    placeable.place(0, -reserve / 2)
                }
            },
        ) {
            // While dragging, the times preview the drag target (Ezzy's ask:
            // show where you're up to while moving), in the row that already
            // exists - no new bubble. Live everywhere, inside the circle too.
            val previewSec = dragPos?.takeIf { length > 0 }?.let { (it * length).toLong() } ?: s?.timeSec ?: 0
            val previewRemain = (length - previewSec).coerceAtLeast(0)
            Text(formatTime(previewSec), color = Orange, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = tight)
            Spacer(Modifier.weight(1f))
            Text("-${formatTime(previewRemain)}", color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = tight)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onGloballyPositioned { drag.barOrigin = it.localToWindow(Offset.Zero) }
                .pointerInput(length) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressing = true
                        RawTouch.cancelAt = 0L
                        var holdJob: Job? = null
                        if (!unlockedNow) {
                            holdJob = scope.launch {
                                delay(500)
                                tapHaptic(view, remote.store.haptics)
                                unlocked = true
                                idleStamp = System.currentTimeMillis()
                            }
                        }
                        var moved = false
                        var dragging = false
                        var cleanRelease = false
                        var seekTarget: Float? = null
                        var lastDragLogMs = 0L
                        try {
                            var up = false
                            while (!up) {
                                val event = awaitPointerEvent()
                                // The first pointer owns the gesture; other fingers are ignored.
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    if (!moved && kotlin.math.abs(change.position.x - down.position.x) > 14f) moved = true
                                    if (unlockedNow && (moved || dragging)) {
                                        dragging = true
                                        holdJob?.cancel()
                                        // The thumb follows the FINGER - absolute x over the
                                        // track, everywhere including inside the cancel
                                        // circle, so the counters stay live.
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        dragPos = (change.position.x / w).coerceIn(0f, 1f)
                                        drag.active = true
                                        drag.trackCircle(drag.barOrigin + change.position, rampPx, view, remote.store.haptics)
                                        idleStamp = System.currentTimeMillis()
                                        if (BuildConfig.DEBUG && length > 0) {
                                            val nowMs = System.currentTimeMillis()
                                            if (drag.inCircle || nowMs - lastDragLogMs >= 200) {
                                                lastDragLogMs = nowMs
                                                Log.i("BMSeek", "drag sec=${(dragPos!! * length).toLong()} inCircle=${drag.inCircle}")
                                            }
                                        }
                                    }
                                    change.consume()
                                } else {
                                    up = true
                                    // Only a real finger-up counts; a system cancel
                                    // arrives as the same synthetic release shape.
                                    cleanRelease = RawTouch.cancelAt == 0L
                                }
                            }
                        } catch (e: CancellationException) {
                            // Interrupted (system grab, nav swipe, call): no seek, no toggle.
                        } finally {
                            holdJob?.cancel()
                            pressing = false
                            val p = dragPos
                            val wasInCircle = drag.inCircle
                            dragPos = null
                            drag.active = false
                            drag.inCircle = false
                            drag.proximity = 0f
                            if (unlockedNow) idleStamp = System.currentTimeMillis()
                            // Only a clean release outside the circle seeks. Inside the
                            // circle cancels (the place is kept); any interrupted or
                            // abnormal end does nothing at all.
                            if (cleanRelease && dragging && p != null && length > 0 && !wasInCircle) {
                                seekTarget = p
                            }
                        }
                        val st = seekTarget
                        if (st != null) {
                            tapHaptic(view, remote.store.haptics)
                            pendingSeek = st to System.currentTimeMillis()
                            remote.seekTo((st * length).toLong())
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(TrackGray))
            // Fill
            val shown = dragPos ?: pendingSeek?.takeIf { (f, t) ->
                System.currentTimeMillis() - t < 2500 && kotlin.math.abs(f - position) > 0.01f }?.first ?: position
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
                if (unlocked) "Unlocked. Locks again in a few seconds" else "Hold the bar to unlock seeking",
                color = if (unlocked) Orange else Muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = tight,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${formatTime(length)} total", color = Muted, fontSize = 12.sp, maxLines = 1, style = tight)
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
private fun VolumeRow(remote: RemoteState, flashAll: Boolean, gapDp: Float, scale: Float = 1f) {
    val s = remote.status
    val muted = remote.muted
    Row(
        Modifier.fillMaxWidth().padding(horizontal = gapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressTile(
            onTap = { remote.volumeStep(false) },
            onHoldRepeat = { remote.volumeStep(false) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((84 * scale).dp),
            desc = "Volume down",
        ) { pressed ->
            VolumeDownIcon(if (pressed && flashAll) Orange else TextC, size = 30.dp, extraWave = pressed && flashAll)
        }
        Spacer(Modifier.width(gapDp.dp))
        // Percent readout: tap toggles mute. Muted: dimmed 0% + MUTED, thin red strike corner to corner.
        PressTile(
            onTap = { remote.toggleMute() },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.width(108.dp).height((84 * scale).dp), // same width as Play/Pause, so the columns line up
            desc = "Mute",
        ) { pressed ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Approved layout: block sits 3dp low of center; VOL hugs the number 4dp tighter than default spacing.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 3.dp)) {
                    Text(
                        if (muted) "0%" else "${remote.winVolume ?: (s?.volumePct ?: 0)}%",
                        color = if (muted) Dimmed else if (pressed && flashAll) Orange else TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (muted) "MUTED" else "VOL",
                        color = if (muted) Dimmed else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(y = (-4).dp),
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
        Spacer(Modifier.width(gapDp.dp))
        PressTile(
            onTap = { remote.volumeStep(true) },
            onHoldRepeat = { remote.volumeStep(true) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((84 * scale).dp),
            desc = "Volume up",
        ) { pressed ->
            VolumeUpIcon(if (pressed && flashAll) Orange else TextC, size = 30.dp, extraWave = pressed && flashAll)
        }
    }
}

@Composable
private fun SkipRow(remote: RemoteState, flashAll: Boolean, gapDp: Float, scale: Float = 1f, seekDrag: SeekDrag? = null) {
    val label = formatSkip(remote.store.skipMainSec)
    val playing = remote.status?.playing == true
    Row(
        Modifier.fillMaxWidth().padding(horizontal = gapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressTile(
            onTap = { remote.skip(-remote.store.skipMainSec) },
            onHoldRepeat = { remote.skip(-remote.store.skipMainSec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((108 * scale).dp),
            desc = "Back $label",
        ) { pressed ->
            SkipIcon(forward = false, label = label, pressed = pressed, tint = TextC, size = 44.dp, ringScale = 40f / 44f)
        }
        Spacer(Modifier.width(gapDp.dp))
        PlayPauseButton(remote, playing, flashAll, seekDrag = seekDrag)
        Spacer(Modifier.width(gapDp.dp))
        PressTile(
            onTap = { remote.skip(remote.store.skipMainSec) },
            onHoldRepeat = { remote.skip(remote.store.skipMainSec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((108 * scale).dp),
            desc = "Forward $label",
        ) { pressed ->
            SkipIcon(forward = true, label = label, pressed = pressed, tint = TextC, size = 44.dp, ringScale = 40f / 44f)
        }
    }
}

@Composable
private fun PlayPauseButton(remote: RemoteState, playing: Boolean, flashAll: Boolean, scale: Float = 1f, seekDrag: SeekDrag? = null) {
    // During a seek drag this circle is the cancel target, not a button: the tap
    // stays play/pause only when no drag is active.
    val cancelling = seekDrag?.active == true
    PressTile(
        onTap = { if (!cancelling) remote.togglePause() },
        hapticsOn = remote.store.haptics,
        desc = if (playing) "Pause" else "Play",
        background = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Card2, Card)),
        modifier = Modifier
            .size((108 * scale).dp)
            .then(if (seekDrag != null) Modifier.onGloballyPositioned { c -> seekDrag.circle = Rect(c.localToWindow(Offset.Zero), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat())) } else Modifier),
        corner = (54 * scale).dp,
    ) { pressed ->
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
            if (cancelling && seekDrag != null) {
                CancelCircleContent(seekDrag, remote.store.cancelStyle)
            } else {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawCircle(if (pressed && flashAll) TextC else Orange, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                }
                if (playing) PauseIcon(Orange, 40.dp) else PlayIcon(Orange, 40.dp)
            }
        }
    }
}

/** The cancel target that replaces the play/pause glyph during a seek drag.
 * "Ring only": orange ring and label from the start, brightening as the finger
 * closes in. "Fills in": the orange outline is constant and the interior fills
 * dark-to-orange with proximity; the label darkens early so it stays readable.
 * Either way only the wording flips at the border: Cancel -> Release to cancel. */
@Composable
private fun CancelCircleContent(drag: SeekDrag, style: String) {
    val p = drag.proximity
    val dark = Color(0xFF1B1B1E)
    val fills = style == "fills"
    val ringColor = androidx.compose.ui.graphics.lerp(Orange, OrangeLight, p)
    val labelColor = if (fills) {
        androidx.compose.ui.graphics.lerp(Orange, dark, (p * 3f).coerceAtMost(1f))
    } else ringColor
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (fills) drawCircle(androidx.compose.ui.graphics.lerp(Card, Orange, p))
            drawCircle(ringColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
        Text(
            if (drag.inCircle) "Release to cancel" else "Cancel",
            color = labelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun SecondarySkipRow(remote: RemoteState, gapDp: Float, scale: Float = 1f) {
    val label = formatSkip(remote.store.skipSecondarySec)
    // Ezzy's call: the 30s ring + label are the exact 5s size, fixed. The tiles
    // grew into the space the removed skip-hint line freed (8dp spacer + ~16dp
    // text line), so the row top and everything below stay exactly where they
    // were and the icon sits centered.
    Row(Modifier.fillMaxWidth().padding(horizontal = gapDp.dp)) {
        PressTile(
            onTap = { remote.skip(-remote.store.skipSecondarySec) },
            onHoldRepeat = { remote.skip(-remote.store.skipSecondarySec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((86 * scale).dp),
            desc = "Back $label",
        ) { pressed ->
            SkipIcon(forward = false, label = label, pressed = pressed, tint = Orange, size = 44.dp, labelScale = 0.30f, ringScale = 40f / 44f)
        }
        Spacer(Modifier.width(gapDp.dp))
        PressTile(
            onTap = { remote.skip(remote.store.skipSecondarySec) },
            onHoldRepeat = { remote.skip(remote.store.skipSecondarySec) },
            hapticsOn = remote.store.haptics,
            modifier = Modifier.weight(1f).height((86 * scale).dp),
            desc = "Forward $label",
        ) { pressed ->
            SkipIcon(forward = true, label = label, pressed = pressed, tint = Orange, size = 44.dp, labelScale = 0.30f, ringScale = 40f / 44f)
        }
    }
}

@Composable
private fun BottomRow(remote: RemoteState, flashAll: Boolean, gapDp: Float, onSubtitles: () -> Unit, onMovies: () -> Unit, onMouse: () -> Unit, scale: Float = 1f) {
    // 1.5.0: icons only, no captions. Order: Movies, Subtitles, Fullscreen, Mouse.
    Row(Modifier.fillMaxWidth().padding(horizontal = gapDp.dp)) {
        PressTile(onTap = onMovies, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height((80 * scale).dp), desc = "Movies") { pressed ->
            FolderIcon(if (pressed && flashAll) Orange else TextC, size = 47.5.dp)
        }
        Spacer(Modifier.width(gapDp.dp))
        PressTile(onTap = onSubtitles, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height((80 * scale).dp), desc = "Subtitles") { pressed ->
            SubtitleIcon(if (pressed && flashAll) Orange else TextC, size = 47.5.dp)
        }
        Spacer(Modifier.width(gapDp.dp))
        PressTile(onTap = { remote.toggleFullscreen() }, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height((80 * scale).dp), desc = "Fullscreen") { pressed ->
            FullscreenIcon(if (pressed && flashAll) Orange else TextC, size = 47.5.dp, pressed = pressed && flashAll)
        }
        Spacer(Modifier.width(gapDp.dp))
        PressTile(onTap = onMouse, hapticsOn = remote.store.haptics, modifier = Modifier.weight(1f).height((80 * scale).dp), desc = "Mouse") { pressed ->
            CursorIcon(if (pressed && flashAll) Orange else TextC, size = 46.dp)
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
            SubtitleRow("Off", picked == -1 || (picked == null && tracks.none { it.selected }), remote.store.haptics) {
                remote.pickSubtitle(-1); onClose()
            }
            tracks.forEach { t ->
                val checked = if (picked != null) picked == t.id else t.selected
                SubtitleRow(t.name, checked, remote.store.haptics) {
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
private fun SubtitleRow(name: String, checked: Boolean, hapticsOn: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                tapHaptic(view, hapticsOn)
                onClick()
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

