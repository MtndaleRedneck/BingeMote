package com.bingemote

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "sink")
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (pressed) 2.dp else 6.dp, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner))
            .background(background)
            .pointerInput(onTap, onHoldRepeat, hapticsOn) {
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view, hapticsOn)
                    pressed = true
                    var repeatJob: Job? = null
                    if (onHoldRepeat != null) {
                        repeatJob = scope.launch {
                            delay(400)
                            while (true) {
                                tapHaptic(view, hapticsOn)
                                onHoldRepeat()
                                delay(320)
                            }
                        }
                    }
                    val up = waitForUpOrCancellation()
                    repeatJob?.cancel()
                    pressed = false
                    if (up != null) onTap()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(pressed) }
}

/** Header: the BingeMote logo + app name + trailing buttons. */
@Composable
fun TopBar(trailing: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(8.dp))
        // 1.5.0: the title wraps its text and the trailing lambda gets two
        // weighted spacers around the status pill, so the pill centers itself in
        // the free room between the name and Settings with equal gaps.
        Text(
            "BingeMote", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
fun HeaderIconButton(onClick: () -> Unit, hapticsOn: Boolean = true, startPad: Dp = 10.dp, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .padding(start = startPad)
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HeaderBtn)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view, hapticsOn)
                    if (waitForUpOrCancellation() != null) onClick()
                }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun TileLabel(text: String, color: Color, size: Int = 14, bold: Boolean = true) {
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}
