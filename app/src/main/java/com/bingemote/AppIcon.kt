package com.bingemote

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
