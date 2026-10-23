package com.bingemote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded-corner polygon like the approved artwork: cut each corner by rr along
 * both edges and ease through it with a quadratic. */
private fun roundedPoly(pts: List<Offset>, r: Float): Path {
    val n = pts.size
    fun cut(i: Int): Pair<Offset, Offset> {
        val p0 = pts[(i - 1 + n) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]
        val v1 = p0 - p1; val v2 = p2 - p1
        val l1 = v1.getDistance(); val l2 = v2.getDistance()
        val rr = minOf(r, l1 / 2.2f, l2 / 2.2f)
        return p1 + v1 / l1 * rr to p1 + v2 / l2 * rr
    }
    val path = Path()
    val (a0, _) = cut(0)
    path.moveTo(a0.x, a0.y)
    for (i in 0 until n) {
        val (a, b) = cut(i)
        path.lineTo(a.x, a.y)
        path.quadraticTo(pts[i].x, pts[i].y, b.x, b.y)
    }
    path.close()
    return path
}

/** Mouse cursor arrow. */
@Composable
fun CursorIcon(color: Color, size: Dp = 21.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        // Thin outline pointer, Ezzy's pick: rounded joins, same weight as the other
        // bottom-row icons.
        val pts = listOf(
            Offset(w * 0.330f, w * 0.177f), Offset(w * 0.330f, w * 0.789f),
            Offset(w * 0.483f, w * 0.653f), Offset(w * 0.594f, w * 0.874f),
            Offset(w * 0.696f, w * 0.823f), Offset(w * 0.585f, w * 0.611f),
            Offset(w * 0.772f, w * 0.602f),
        )
        drawPath(roundedPoly(pts, w * 0.034f), color,
            style = Stroke(width = w * 0.0357f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
fun VolumeDownIcon(color: Color, size: Dp = 22.dp, extraWave: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawSpeaker(color, w, h)
        drawWave(color, w, h, radius = w * 0.24f, stroke = w * 0.08f)
        if (extraWave) drawWave(color, w, h, radius = w * 0.36f, stroke = w * 0.08f)
    }
}

/** Volume up: speaker with three sound waves (the approved render's look). */
@Composable
fun VolumeUpIcon(color: Color, size: Dp = 22.dp, extraWave: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawSpeaker(color, w, h)
        if (extraWave) {
            // Pressed pulse: four tighter rings - a 4th ring at the resting spacing
            // would clip the icon's right edge, these end at 0.48 + 0.475 + 0.035 < 1.0.
            drawWave(color, w, h, radius = w * 0.13f, stroke = w * 0.07f)
            drawWave(color, w, h, radius = w * 0.245f, stroke = w * 0.07f)
            drawWave(color, w, h, radius = w * 0.36f, stroke = w * 0.07f)
            drawWave(color, w, h, radius = w * 0.475f, stroke = w * 0.07f)
        } else {
            // Approved 1.3.1 render: slimmer waves, wider spacing; outer edge ends exactly
            // at the icon's right edge (0.48 + 0.48 + 0.04 = 1.0), nothing clipped.
            drawWave(color, w, h, radius = w * 0.24f, stroke = w * 0.08f)
            drawWave(color, w, h, radius = w * 0.36f, stroke = w * 0.08f)
            drawWave(color, w, h, radius = w * 0.48f, stroke = w * 0.08f)
        }
    }
}

/** Fullscreen: four corner brackets with rounded turns (approved 1.5.0 vector).
 * [pressed] stretches the corners outward for a beat (the button-flash pulse). */
@Composable
fun FullscreenIcon(color: Color, size: Dp = 22.dp, pressed: Boolean = false) {
    val pop by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1.12f else 1f, label = "pop")
    Canvas(Modifier.size(size).graphicsLayer { scaleX = pop; scaleY = pop }) {
        val w = this.size.width; val h = this.size.height
        val st = w * 0.0357f
        val ins = w * 0.143f; val arm = w * 0.207f; val r = w * 0.061f
        // corner arcs (quarter circles): TL 180->270, TR 270->360, BL 90->180, BR 0->90
        drawArc(color, 180f, 90f, false, Offset(ins, ins), Size(2 * r, 2 * r), style = Stroke(width = st, cap = StrokeCap.Round))
        drawArc(color, 270f, 90f, false, Offset(w - ins - 2 * r, ins), Size(2 * r, 2 * r), style = Stroke(width = st, cap = StrokeCap.Round))
        drawArc(color, 90f, 90f, false, Offset(ins, h - ins - 2 * r), Size(2 * r, 2 * r), style = Stroke(width = st, cap = StrokeCap.Round))
        drawArc(color, 0f, 90f, false, Offset(w - ins - 2 * r, h - ins - 2 * r), Size(2 * r, 2 * r), style = Stroke(width = st, cap = StrokeCap.Round))
        // arms
        drawLine(color, Offset(ins + r, ins), Offset(ins + arm, ins), st, cap = StrokeCap.Round)
        drawLine(color, Offset(ins, ins + r), Offset(ins, ins + arm), st, cap = StrokeCap.Round)
        drawLine(color, Offset(w - ins - r, ins), Offset(w - ins - arm, ins), st, cap = StrokeCap.Round)
        drawLine(color, Offset(w - ins, ins + r), Offset(w - ins, ins + arm), st, cap = StrokeCap.Round)
        drawLine(color, Offset(ins + r, h - ins), Offset(ins + arm, h - ins), st, cap = StrokeCap.Round)
        drawLine(color, Offset(ins, h - ins - r), Offset(ins, h - ins - arm), st, cap = StrokeCap.Round)
        drawLine(color, Offset(w - ins - r, h - ins), Offset(w - ins - arm, h - ins), st, cap = StrokeCap.Round)
        drawLine(color, Offset(w - ins, h - ins - r), Offset(w - ins, h - ins - arm), st, cap = StrokeCap.Round)
    }
}

/** Chunky cross arrows for the Keyboard sheet (Ezzy's pick over the thin text
 * glyphs): one filled polygon per direction, sharp clean tips. */
enum class ArrowDir { Up, Down, Left, Right }

@Composable
fun ArrowIcon(dir: ArrowDir, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sh = w * 0.08f   // shaft half-width
        val hw = w * 0.27f   // head half-width
        val hh = w * 0.29f   // head length
        val cx = w / 2
        val pts = when (dir) {
            ArrowDir.Up -> listOf(
                cx - sh to w, cx + sh to w, cx + sh to hh, cx + hw to hh,
                cx to 0f, cx - hw to hh, cx - sh to hh,
            )
            ArrowDir.Down -> listOf(
                cx - sh to 0f, cx + sh to 0f, cx + sh to w - hh, cx + hw to w - hh,
                cx to w, cx - hw to w - hh, cx - sh to w - hh,
            )
            ArrowDir.Left -> listOf(
                w to cx - sh, w to cx + sh, hh to cx + sh, hh to cx + hw,
                0f to cx, hh to cx - hw, hh to cx - sh,
            )
            ArrowDir.Right -> listOf(
                0f to cx - sh, 0f to cx + sh, w - hh to cx + sh, w - hh to cx + hw,
                w to cx, w - hh to cx - hw, w - hh to cx - sh,
            )
        }
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
            close()
        }
        drawPath(path, color)
    }
}

/** Subtitles (approved 1.5.0 vector): hollow chat bubble, sharp hollow tail,
 * three staggered rows of dashed text lines. */
@Composable
fun SubtitleIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val st = w * 0.0357f
        // One continuous outline: the bubble's bottom edge opens where the tail
        // joins, running straight down into the tail and back up (Ezzy's pick).
        val x0 = w * 0.143f; val y0 = h * 0.179f; val x1 = w * 0.857f; val y1 = h * 0.679f
        val r = w * 0.061f
        val tailL = w * 0.339f; val tailR = w * 0.518f
        val tipX = w * 0.265f; val tipY = h * 0.885f
        val bubble = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tailR, y1)
            lineTo(x1 - r, y1)
            arcTo(androidx.compose.ui.geometry.Rect(x1 - 2 * r, y1 - 2 * r, x1, y1), 90f, -90f, false)
            lineTo(x1, y0 + r)
            arcTo(androidx.compose.ui.geometry.Rect(x1 - 2 * r, y0, x1, y0 + 2 * r), 0f, -90f, false)
            lineTo(x0 + r, y0)
            arcTo(androidx.compose.ui.geometry.Rect(x0, y0, x0 + 2 * r, y0 + 2 * r), 270f, -90f, false)
            lineTo(x0, y1 - r)
            arcTo(androidx.compose.ui.geometry.Rect(x0, y1 - 2 * r, x0 + 2 * r, y1), 180f, -90f, false)
            lineTo(tailL, y1)
            close()
        }
        drawPath(bubble, color, style = Stroke(width = st, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = StrokeCap.Round))
        // Interior A1 (Ezzy's pick): three rows, each a long line plus
        // staggered shorter dashes, like real text.
        val rows = listOf(
            0.321f to listOf(0.257f to 0.475f, 0.525f to 0.632f, 0.682f to 0.743f),
            0.429f to listOf(0.257f to 0.311f, 0.361f to 0.579f, 0.629f to 0.743f),
            0.536f to listOf(0.257f to 0.421f, 0.471f to 0.539f, 0.589f to 0.743f),
        )
        for ((ry, segs) in rows) {
            for ((xa, xb) in segs) {
                drawLine(color, Offset(w * xa, h * ry), Offset(w * xb, h * ry), st, cap = StrokeCap.Round)
            }
        }
    }
}

/** Folder. */
@Composable
fun FolderIcon(color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        // Thin outline folder, Ezzy's pick: rounded corners, tab divider line.
        val st = w * 0.0357f
        val pts = listOf(
            Offset(w * 0.177f, w * 0.772f), Offset(w * 0.177f, w * 0.245f),
            Offset(w * 0.449f, w * 0.245f), Offset(w * 0.517f, w * 0.330f),
            Offset(w * 0.823f, w * 0.330f), Offset(w * 0.823f, w * 0.772f),
        )
        drawPath(roundedPoly(pts, w * 0.034f), color,
            style = Stroke(width = st, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.177f, w * 0.415f), Offset(w * 0.823f, w * 0.415f), st, cap = StrokeCap.Round)
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
fun SkipIcon(forward: Boolean, label: String, pressed: Boolean, tint: Color, size: Dp = 34.dp, labelScale: Float = 0.30f, ringScale: Float = 1f) {
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
    // Pressed, the ring flashes the opposite brand color: white rings go orange,
    // orange rings go white. The label keeps its own color either way.
    val ringColor = if (pressed) (if (tint == Orange) TextC else Orange) else tint
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
            // Skip/replay arrow, Ezzy's final pick (Tab's v2 drop-in file): plain
            // butt-ended ring (no tail dot), stroke 28/118 of the radius, arrowhead
            // turned 7.8 deg inward from the tangent.
            drawArc(
                ringColor, startAngle = 130f, sweepAngle = 262f, useCenter = false,
                topLeft = Offset(inset, inset), size = Size(w - inset * 2, w - inset * 2),
                style = Stroke(width = w * 0.095f * ringScale, cap = StrokeCap.Butt),
            )
            val cx = w / 2
            val head = Path().apply {
                moveTo(cx + w * 0.1997f, cx + w * 0.3797f)
                lineTo(cx + w * 0.2573f, cx + w * 0.1251f)
                lineTo(cx + w * 0.4397f, cx + w * 0.2769f)
                close()
            }
            drawPath(head, ringColor)
        }
        // The label is part of the icon artwork: size it off the ring in dp, not sp,
        // so a large system font cannot blow it out of the ring (Ezzy's scale-together rule).
        Text(label, color = tint, fontSize = with(LocalDensity.current) { (size * labelScale).toSp() }, fontWeight = FontWeight.Bold)
    }
}
