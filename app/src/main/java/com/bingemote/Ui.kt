package com.bingemote

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Light tick on press, when the user left haptics on. */
fun tapHaptic(view: View, on: Boolean) {
    if (on) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * A remote-control button: sinks when pressed, fires a haptic, and can repeat
 * while held (skip buttons, volume). [onHoldRepeat] runs about 3x a second while held.
 */
@Composable
fun PressTile(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onHoldRepeat: (() -> Unit)? = null,
    hapticsOn: Boolean = true,
    background: Brush = CardGradient,
    corner: Dp = 18.dp,
    desc: String? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "sink")
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Read the newest callbacks at fire time, so the gesture never restarts mid-press
    // when the screen recomposes (status polls, volume changes).
    val tap by rememberUpdatedState(onTap)
    val repeat by rememberUpdatedState(onHoldRepeat)
    val buzz by rememberUpdatedState(hapticsOn)

    Box(
        modifier
            .semantics {
                if (desc != null) contentDescription = desc
                role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) { tap(); true } else false }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .shadow(if (pressed || !enabled) 2.dp else 6.dp, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner))
            .background(background)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view, buzz)
                    pressed = true
                    var repeatJob: Job? = null
                    try {
                        if (repeat != null) {
                            repeatJob = scope.launch {
                                delay(400)
                                while (true) {
                                    val r = repeat ?: break
                                    tapHaptic(view, buzz)
                                    r()
                                    delay(320)
                                }
                            }
                        }
                        val up = waitForUpOrCancellation()
                        if (up != null) tap()
                    } finally {
                        // Also runs if the gesture is cancelled, so a held skip never keeps going.
                        repeatJob?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(pressed) }
}

/** Header: logo + app name + centered status pill + Settings. The pill centers in the
 * free room between the name and Settings with equal gaps. When space runs short (large
 * font scale), the name shortens with an ellipsis first, then the pill caps and
 * ellipsizes - Settings is always fully on screen. */
@Composable
fun TopBar(pill: @Composable () -> Unit, settings: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        androidx.compose.ui.layout.Layout(content = {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
            )
            Text(
                "BingeMote", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            pill()
            settings()
        }) { measurables, constraints ->
            val gap = 8.dp.roundToPx()
            val logoP = measurables[0].measure(androidx.compose.ui.unit.Constraints())
            val setP = measurables[3].measure(androidx.compose.ui.unit.Constraints())
            val middle = (constraints.maxWidth - logoP.width - gap - setP.width).coerceAtLeast(0)
            // The pill caps at the middle minus a stub of the name; the name gets what is left.
            val minTitle = 56.dp.roundToPx()
            val pillP = measurables[2].measure(androidx.compose.ui.unit.Constraints(maxWidth = (middle - minTitle).coerceAtLeast(0)))
            val titleP = measurables[1].measure(androidx.compose.ui.unit.Constraints(maxWidth = (middle - pillP.width).coerceAtLeast(0)))
            val h = maxOf(logoP.height, titleP.height, pillP.height, setP.height)
            layout(constraints.maxWidth, h) {
                logoP.placeRelative(0, (h - logoP.height) / 2)
                val titleX = logoP.width + gap
                titleP.placeRelative(titleX, (h - titleP.height) / 2)
                val freeStart = titleX + titleP.width
                val freeEnd = constraints.maxWidth - setP.width
                val pillX = freeStart + ((freeEnd - freeStart - pillP.width) / 2).coerceAtLeast(0)
                pillP.placeRelative(pillX, (h - pillP.height) / 2)
                setP.placeRelative(constraints.maxWidth - setP.width, (h - setP.height) / 2)
            }
        }
    }
}

@Composable
fun HeaderIconButton(onClick: () -> Unit, hapticsOn: Boolean = true, startPad: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    // startPad defaults to 0 so back buttons line up with the 16dp screen edge like every card.
    Box(
        Modifier
            .padding(start = startPad)
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HeaderBtn)
            .clickable(role = Role.Button) {
                tapHaptic(view, hapticsOn)
                onClick()
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun TileLabel(text: String, color: Color, size: Int = 14, bold: Boolean = true) {
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

/** Loading, empty and error states for the Movies and folder lists: centered, readable, with a way out. */
@Composable
fun ListMessage(
    text: String,
    isError: Boolean = false,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(color = Orange, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text,
            color = if (isError) TextC else Muted,
            fontSize = 15.sp, lineHeight = 21.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PressTile(
                onTap = onAction,
                background = OrangeGradient,
                modifier = Modifier.width(180.dp).height(48.dp),
                corner = 14.dp,
            ) {
                Text(actionLabel, color = Color(0xFF1B1B1E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One row of the Movies / folder lists: icon on the left, name left-aligned (it used to sit centered). */
@Composable
fun BrowseRowContent(name: String, kind: BrowseKind, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (kind) {
                BrowseKind.Up -> ArrowIcon(ArrowDir.Left, Muted, 20.dp)
                BrowseKind.Folder -> FolderIcon(Orange, 22.dp)
                BrowseKind.Movie -> PlayIcon(Orange, 18.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (kind == BrowseKind.Up) Muted else TextC,
                fontSize = 15.sp, fontWeight = if (kind == BrowseKind.Up) FontWeight.SemiBold else FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}

enum class BrowseKind { Up, Folder, Movie }
