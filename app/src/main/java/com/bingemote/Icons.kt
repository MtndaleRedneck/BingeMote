package com.bingemote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mouse cursor arrow. */
@Composable
fun CursorIcon(color: Color, size: Dp = 21.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.28f, h * 0.12f)
            lineTo(w * 0.28f, h * 0.78f)
            lineTo(w * 0.44f, h * 0.64f)
            lineTo(w * 0.56f, h * 0.88f)
            lineTo(w * 0.66f, h * 0.83f)
            lineTo(w * 0.54f, h * 0.59f)
            lineTo(w * 0.74f, h * 0.59f)
            close()
        }
        drawPath(p, color)
    }
}

/** Speaker body, shared by the volume icons: small base rect + full-height horn. */
private fun DrawScope.drawSpeaker(color: Color, w: Float, h: Float) {
    val p = Path().apply {
        moveTo(w * 0.02f, h * 0.34f)
        lineTo(w * 0.20f, h * 0.34f)
        lineTo(w * 0.48f, h * 0.04f)
        lineTo(w * 0.48f, h * 0.96f)
        lineTo(w * 0.20f, h * 0.66f)
        lineTo(w * 0.02f, h * 0.66f)
        close()
    }
    drawPath(p, color)
}

/** One sound wave: a bold arc right of the speaker's mouth, round caps. */
private fun DrawScope.drawWave(color: Color, w: Float, h: Float, radius: Float, stroke: Float) {
    val cx = w * 0.48f; val cy = h * 0.5f
    drawArc(
        color, startAngle = -45f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(cx - radius, cy - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/** Volume down (approved v4): speaker with Vol up's inner wave alone - down reads
 * as "up minus the outer two rings", a matched pair with VolumeUpIcon. */
@Composable
fun VolumeDownIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawSpeaker(color, w, h)
        drawWave(color, w, h, radius = w * 0.24f, stroke = w * 0.08f)
    }
}

/** Volume up: speaker with three sound waves (the approved render's look). */
@Composable
fun VolumeUpIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawSpeaker(color, w, h)
        // Approved 1.3.1 render: slimmer waves, wider spacing; outer edge ends exactly
        // at the icon's right edge (0.48 + 0.48 + 0.04 = 1.0), nothing clipped.
        drawWave(color, w, h, radius = w * 0.24f, stroke = w * 0.08f)
        drawWave(color, w, h, radius = w * 0.36f, stroke = w * 0.08f)
        drawWave(color, w, h, radius = w * 0.48f, stroke = w * 0.08f)
    }
}

/** Four fullscreen corner brackets. */
@Composable
fun FullscreenIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        val inset = w * 0.12f; val len = w * 0.26f
        val lines = listOf(
            // top-left
            Offset(inset, inset + len) to Offset(inset, inset), Offset(inset, inset) to Offset(inset + len, inset),
            // top-right
            Offset(w - inset - len, inset) to Offset(w - inset, inset), Offset(w - inset, inset) to Offset(w - inset, inset + len),
            // bottom-left
            Offset(inset, h - inset - len) to Offset(inset, h - inset), Offset(inset, h - inset) to Offset(inset + len, h - inset),
            // bottom-right
            Offset(w - inset - len, h - inset) to Offset(w - inset, h - inset), Offset(w - inset, h - inset) to Offset(w - inset, h - inset - len),
        )
        for ((a, b) in lines) drawLine(color, a, b, strokeWidth = st.width, cap = StrokeCap.Round)
    }
}

/** Subtitle bubble: rounded rect with two text lines. */
@Composable
fun SubtitleIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(color, topLeft = Offset(w * 0.10f, h * 0.18f), size = Size(w * 0.80f, h * 0.64f), cornerRadius = CornerRadius(w * 0.12f), style = Stroke(width = w * 0.07f))
        drawLine(color, Offset(w * 0.22f, h * 0.42f), Offset(w * 0.70f, h * 0.42f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, h * 0.60f), Offset(w * 0.54f, h * 0.60f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** Folder. */
@Composable
fun FolderIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.26f)
            lineTo(w * 0.38f, h * 0.26f)
            lineTo(w * 0.46f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.36f)
            lineTo(w * 0.90f, h * 0.78f)
            lineTo(w * 0.10f, h * 0.78f)
            close()
        }
        drawPath(p, color, style = Stroke(width = w * 0.07f, cap = StrokeCap.Round))
    }
}

/** Little keyboard. */
@Composable
fun KeyboardIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(color, topLeft = Offset(w * 0.08f, h * 0.22f), size = Size(w * 0.84f, h * 0.56f), cornerRadius = CornerRadius(w * 0.10f), style = Stroke(width = w * 0.07f))
        val keyW = w * 0.10f
        for (r in 0..1) for (c in 0..2) {
            drawRoundRect(color, topLeft = Offset(w * 0.18f + c * w * 0.22f, h * 0.33f + r * h * 0.18f), size = Size(keyW, h * 0.09f), cornerRadius = CornerRadius(2f))
        }
        drawRoundRect(color, topLeft = Offset(w * 0.26f, h * 0.62f), size = Size(w * 0.48f, h * 0.08f), cornerRadius = CornerRadius(2f))
    }
}

/** Play triangle. */
@Composable
fun PlayIcon(color: Color, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.34f, h * 0.20f)
            lineTo(w * 0.34f, h * 0.80f)
            lineTo(w * 0.82f, h * 0.50f)
            close()
        }
        drawPath(p, color)
    }
}

/** Pause bars. */
@Composable
fun PauseIcon(color: Color, size: Dp = 40.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(color, topLeft = Offset(w * 0.28f, h * 0.20f), size = Size(w * 0.15f, h * 0.60f), cornerRadius = CornerRadius(w * 0.05f))
        drawRoundRect(color, topLeft = Offset(w * 0.57f, h * 0.20f), size = Size(w * 0.15f, h * 0.60f), cornerRadius = CornerRadius(w * 0.05f))
    }
}

/**
 * Circular skip arrow with the value in the middle. The ring is a 300-degree arc
 * with an arrowhead; forward spins clockwise, back is mirrored. On each tap the ring
 * does one full turn (Ezzy's ask: "I want it to fully rotate"); the number stays put.
 */
@Composable
fun SkipIcon(forward: Boolean, label: String, pressed: Boolean, tint: Color, size: Dp = 34.dp) {
    val spin = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            try {
                spin.snapTo(0f)
                spin.animateTo(if (forward) 360f else -360f, tween(420, easing = FastOutSlowInEasing))
            } finally {
                // A quick tap cancels the spin mid-turn; always settle back upright.
                spin.snapTo(0f)
            }
        }
    }
    val rotation = spin.value
    val ringColor = if (pressed) Orange else tint
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            rotationZ = rotation
            // The 1.3.0 artwork, scaled down so the arrowhead's corner stays inside
            // the box at every spin angle (its corner reaches 0.53x the width from
            // center unscaled, which clipped against the edge mid-spin).
            val k = 0.88f
            scaleX = (if (forward) 1f else -1f) * k
            scaleY = k
        }) {
            val w = this.size.width
            val inset = w * 0.10f
            // Geometry measured from Ezzy's approved target (430-canvas spec, mapped
            // onto center 0.5w / radius 0.4w): ring stroke 28/118 of radius, butt end
            // at the head so no round cap pokes out, fat round dot on the tail,
            // arrowhead rotated ~8 deg inward from the tangent so it reads centered.
            drawArc(
                ringColor, startAngle = 130f, sweepAngle = 262f, useCenter = false,
                topLeft = Offset(inset, inset), size = Size(w - inset * 2, w - inset * 2),
                style = Stroke(width = w * 0.095f, cap = StrokeCap.Butt),
            )
            val cx = w / 2
            val r = w * 0.4f
            val tail = 130f * (Math.PI / 180f)
            drawCircle(
                ringColor, radius = w * 0.059f,
                center = Offset(cx + r * cos(tail).toFloat(), cx + r * sin(tail).toFloat()),
            )
            val head = Path().apply {
                moveTo(cx + w * 0.2207f, cx + w * 0.4043f)
                lineTo(cx + w * 0.2782f, cx + w * 0.1494f)
                lineTo(cx + w * 0.4607f, cx + w * 0.3016f)
                close()
            }
            drawPath(head, ringColor)
        }
        Text(label, color = tint, fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.Bold)
    }
}
