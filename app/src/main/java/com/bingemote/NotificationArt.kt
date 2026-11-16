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

    /** Speaker body shared by the volume pair (Icons.kt drawSpeaker). */
    private fun drawSpeaker(c: Canvas, p: Paint, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.02f, h * 0.34f)
            lineTo(w * 0.20f, h * 0.34f)
            lineTo(w * 0.48f, h * 0.04f)
            lineTo(w * 0.48f, h * 0.96f)
            lineTo(w * 0.20f, h * 0.66f)
            lineTo(w * 0.02f, h * 0.66f)
            close()
        }
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
