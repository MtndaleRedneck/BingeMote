package com.bingemote

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Header app icon. Dynamic path: the app's own launcher AdaptiveIconDrawable drawn through the
 * DEVICE's default icon mask (the drawable applies config_icon_mask itself). No manual crop.
 * Fallback only when the drawable is missing/not adaptive: the existing app_logo art clipped to the
 * AOSP squircle overlay path. This does not detect a launcher whose shape differs from the
 * device default; no Samsung shape is assumed.
 */
@Composable
fun AppIcon(size: Dp = 34.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // Fresh, unshared instance (newDrawable) so bounds are never mutated on a shared drawable.
    val drawable = remember(ctx.resources.configuration) {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
        (d?.constantState?.newDrawable(ctx.resources)?.mutate() ?: d) as? AdaptiveIconDrawable
    }
    if (drawable != null) {
        Canvas(modifier.size(size)) {
            drawIntoCanvas { c ->
                drawable.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt())
                drawable.draw(c.nativeCanvas)
            }
        }
    } else {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = modifier.size(size).clip(AospSquircle),
        )
    }
}

// AOSP IconShapeSquircleOverlay config_icon_mask, 100x100 viewport:
// https://android.googlesource.com/platform/frameworks/base/+/df1ba00dd9f6/packages/overlays/IconShapeSquircleOverlay/res/values/config.xml
internal val AospSquircle = GenericShape { s, _ ->
    val k = s.width / 100f
    fun x(v: Float) = v * k
    moveTo(x(50f), x(0f))
    cubicTo(x(10f), x(0f), x(0f), x(10f), x(0f), x(50f))
    cubicTo(x(0f), x(90f), x(10f), x(100f), x(50f), x(100f))
    cubicTo(x(90f), x(100f), x(100f), x(90f), x(100f), x(50f))
    cubicTo(x(100f), x(10f), x(90f), x(0f), x(50f), x(0f))
    close()
}

/** DEBUG capture only (MainActivity "icondemo"): play/pause in the Home ring (both states, equilateral vs old), the
 *  dynamic AppIcon at 34dp, the app_logo fallback with the AOSP squircle, and a TEST-ONLY proxy that clips the
 *  adaptive layers with the AOSP squircle (labeled proxy for a non-default mask, not any OEM's real shape). */
@Composable
fun DebugIconDemo() {
    val ctx = LocalContext.current
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Bg).padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            for (mode in 0..2) {
                androidx.compose.foundation.layout.Box(Modifier.size(80.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Canvas(Modifier.size(80.dp)) { drawCircle(Orange, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())) }
                    when (mode) {
                        0 -> PlayIcon(Orange, 40.dp)               // old
                        1 -> PlayIcon(Orange, 40.dp, equilateral = true)
                        else -> PauseIcon(Orange, 40.dp)
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            AppIcon(34.dp)
            Image(painter = painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(34.dp).clip(AospSquircle))
            val ad = remember { androidx.core.content.ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as? AdaptiveIconDrawable }
            if (ad != null) Canvas(Modifier.size(34.dp)) {
                val s = size.width
                val path = androidx.compose.ui.graphics.Path().apply {
                    val k = s / 100f
                    moveTo(50f * k, 0f); cubicTo(10f * k, 0f, 0f, 10f * k, 0f, 50f * k); cubicTo(0f, 90f * k, 10f * k, 100f * k, 50f * k, 100f * k)
                    cubicTo(90f * k, 100f * k, 100f * k, 90f * k, 100f * k, 50f * k); cubicTo(100f * k, 10f * k, 90f * k, 0f, 50f * k, 0f); close()
                }
                clipPath(path) {
                    drawIntoCanvas { c ->
                        val full = (s * 108f / 72f).toInt(); val off = ((full - s) / 2f).toInt()
                        ad.background?.setBounds(-off, -off, -off + full, -off + full); ad.background?.draw(c.nativeCanvas)
                        ad.foreground?.setBounds(-off, -off, -off + full, -off + full); ad.foreground?.draw(c.nativeCanvas)
                    }
                }
            }
        }
    }
}
