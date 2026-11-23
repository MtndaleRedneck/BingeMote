package com.bingemote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Compact-bar button icons for the playback notification, drawn at runtime from the
 * exact geometry of Icons.kt so the shade buttons match the in-app buttons pixel for
 * pixel. RemoteViews cannot host Compose, so the same shapes are replayed onto
 * bitmaps with android.graphics (fill paths, arcs, round rects, one text label).
 * Icon color is the app's TextC off-white, on the system's dark notification card.
 */
object NotificationArt {
    private const val TEXT_C = 0xFFF5F5F7.toInt()

    private fun bitmap(sizePx: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_C }
        draw(c, p)
        return b
    }


    /** Content bounding box (alpha > 8) of a drawn icon bitmap; (0,0,0,0) when empty. */
    fun contentBounds(b: Bitmap): android.graphics.Rect {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bot = -1
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                if ((px[off + x] ushr 24) > 8) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > bot) bot = y
                }
            }
        }
        return if (r < 0) android.graphics.Rect(0, 0, 0, 0) else android.graphics.Rect(l, t, r + 1, bot + 1)
    }

    /**
     * Normalize a bar glyph so all five share ONE centered content box. Ezzy's bar
     * measured uneven edge gaps (65/97/68/63 px between glyph edges) because each
     * glyph carried its own size and margins (the play triangle alone had a 12dp
     * left margin). With one shared content width and centered placement, the equal
     * notification slots give perfectly even edge gaps. boxH only caps unusually
     * tall art. Render the source large (48dp) for quality.
     */
    private const val BAR_BOX_W = 24f / 36f
    private const val BAR_BOX_H = 32.8f / 36f
    fun forBar(src: Bitmap, outPx: Int): Bitmap {
        val r = contentBounds(src)
        val out = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
        if (r.width() <= 0 || r.height() <= 0) return out
        val scale = minOf(outPx * BAR_BOX_W / r.width(), outPx * BAR_BOX_H / r.height())
        val dw = r.width() * scale
        val dh = r.height() * scale
        val dst = RectF((outPx - dw) / 2f, (outPx - dh) / 2f, (outPx + dw) / 2f, (outPx + dh) / 2f)
        Canvas(out).drawBitmap(src, android.graphics.Rect(r.left, r.top, r.right, r.bottom), dst,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        return out
    }

    /** Play triangle (Icons.kt PlayIcon). */
    fun play(sizePx: Int): Bitmap = bitmap(sizePx) { c, p ->
        val w = sizePx.toFloat(); val h = w
        p.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.20f)
            lineTo(w * 0.34f, h * 0.80f)
            lineTo(w * 0.82f, h * 0.50f)
            close()
        }
        c.drawPath(path, p)
    }

    /** Pause bars (Icons.kt PauseIcon). */
    fun pause(sizePx: Int): Bitmap = bitmap(sizePx) { c, p ->
        val w = sizePx.toFloat(); val h = w
        p.style = Paint.Style.FILL
        val r = w * 0.05f
        c.drawRoundRect(RectF(w * 0.28f, h * 0.20f, w * 0.28f + w * 0.15f, h * 0.20f + h * 0.60f), r, r, p)
        c.drawRoundRect(RectF(w * 0.57f, h * 0.20f, w * 0.57f + w * 0.15f, h * 0.20f + h * 0.60f), r, r, p)
    }

    /** Speaker body shared by the volume pair (Icons.kt drawSpeaker): same six
     * points, same corner blunting (roundedPoly, r = 0.035w, quadratic eased) so the
     * shade buttons match the in-app buttons pixel for pixel. */
    private fun drawSpeaker(c: Canvas, p: Paint, w: Float, h: Float) {
        val pts = arrayOf(
            floatArrayOf(w * 0.02f, h * 0.34f), floatArrayOf(w * 0.20f, h * 0.34f),
            floatArrayOf(w * 0.48f, h * 0.04f), floatArrayOf(w * 0.48f, h * 0.96f),
            floatArrayOf(w * 0.20f, h * 0.66f), floatArrayOf(w * 0.02f, h * 0.66f),
        )
        val n = pts.size
        val r = w * 0.035f
        fun cut(i: Int): Pair<FloatArray, FloatArray> {
            val p0 = pts[(i - 1 + n) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]
            val v1x = p0[0] - p1[0]; val v1y = p0[1] - p1[1]
            val v2x = p2[0] - p1[0]; val v2y = p2[1] - p1[1]
            val l1 = Math.hypot(v1x.toDouble(), v1y.toDouble()).toFloat()
            val l2 = Math.hypot(v2x.toDouble(), v2y.toDouble()).toFloat()
            val rr = minOf(r, l1 / 2.2f, l2 / 2.2f)
            return floatArrayOf(p1[0] + v1x / l1 * rr, p1[1] + v1y / l1 * rr) to
                floatArrayOf(p1[0] + v2x / l2 * rr, p1[1] + v2y / l2 * rr)
        }
        val path = Path()
        val (a0, _) = cut(0)
        path.moveTo(a0[0], a0[1])
        for (i in 0 until n) {
            val (a, b) = cut(i)
            path.lineTo(a[0], a[1])
            path.quadTo(pts[i][0], pts[i][1], b[0], b[1])
        }
        path.close()
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
    }

    /** One sound wave (Icons.kt drawWave): arc right of the speaker mouth, round caps. */
    private fun drawWave(c: Canvas, p: Paint, w: Float, h: Float, radius: Float, stroke: Float) {
        val cx = w * 0.48f; val cy = h * 0.5f
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), -45f, 90f, false, p)
        p.style = Paint.Style.FILL
    }

    /** Volume down (Icons.kt VolumeDownIcon): speaker with the inner wave alone. */
    fun volumeDown(sizePx: Int): Bitmap = bitmap(sizePx) { c, p ->
        val w = sizePx.toFloat(); val h = w
        drawSpeaker(c, p, w, h)
        drawWave(c, p, w, h, radius = w * 0.24f, stroke = w * 0.08f)
    }

    /** Volume up (Icons.kt VolumeUpIcon): speaker with three waves. */
    fun volumeUp(sizePx: Int): Bitmap = bitmap(sizePx) { c, p ->
        val w = sizePx.toFloat(); val h = w
        drawSpeaker(c, p, w, h)
        drawWave(c, p, w, h, radius = w * 0.24f, stroke = w * 0.08f)
        drawWave(c, p, w, h, radius = w * 0.36f, stroke = w * 0.08f)
        drawWave(c, p, w, h, radius = w * 0.48f, stroke = w * 0.08f)
    }

    /**
     * Circular skip arrow with the seconds in the middle (Icons.kt SkipIcon, resting
     * state - the spin animation has no meaning in a notification). A 262-degree
     * butt-capped ring with the arrowhead turned inward, mirrored for back, label
     * sized off the ring (labelScale 0.30) and never rotated.
     */
    fun skip(sizePx: Int, forward: Boolean, label: String): Bitmap = bitmap(sizePx) { c, p ->
        val w = sizePx.toFloat()
        val cx = w / 2
        val k = 0.88f // the in-app artwork's spin-safe scale; keeps the look identical
        c.save()
        c.scale((if (forward) 1f else -1f) * k, k, cx, cx)
        val inset = w * 0.10f
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.095f
        p.strokeCap = Paint.Cap.BUTT
        c.drawArc(RectF(inset, inset, w - inset, w - inset), 130f, 262f, false, p)
        p.style = Paint.Style.FILL
        val head = Path().apply {
            moveTo(cx + w * 0.1997f, cx + w * 0.3797f)
            lineTo(cx + w * 0.2573f, cx + w * 0.1251f)
            lineTo(cx + w * 0.4397f, cx + w * 0.2769f)
            close()
        }
        c.drawPath(head, p)
        c.restore()
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = w * 0.30f
        val baseline = cx - (p.descent() + p.ascent()) / 2f
        c.drawText(label, cx, baseline, p)
    }
}
