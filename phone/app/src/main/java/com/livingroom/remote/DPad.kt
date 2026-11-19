package com.livingroom.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/** The arrows and OK: four curved keys with gaps between them, around a black OK. */
class DPad(ctx: Context, private val s: Scale) : View(ctx) {
    var onKey: (String) -> Unit = {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(26, 255, 255, 255) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val okFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x0c, 0x0d, 0x0f) }
    private val okRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(90, 255, 255, 255) }
    private val okText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val seg = Path()
    private val outer = RectF(); private val inner = RectF()
    private var pressed: String? = null

    // Sizes in the 300-unit square the design was drawn in.
    private val R = 108f; private val IN = 62f; private val OK = 54f; private val HALF = 28f

    private fun u() = width / 300f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val k = u(); val c = w / 2f
        fill.shader = RadialGradient(c, c, R * k, intArrayOf(Color.rgb(0x18, 0x19, 0x1d), Color.rgb(0x18, 0x19, 0x1d), Color.rgb(0x34, 0x36, 0x3d)),
            floatArrayOf(0f, IN / R, 1f), Shader.TileMode.CLAMP)
        edge.strokeWidth = maxOf(1f, k)
        arrow.strokeWidth = 3f * k
        okRing.strokeWidth = 1.5f * k
        okText.textSize = 28f * k
    }

    override fun onDraw(c: Canvas) {
        val k = u(); val cx = width / 2f; val cy = height / 2f
        outer.set(cx - R * k, cy - R * k, cx + R * k, cy + R * k)
        inner.set(cx - IN * k, cy - IN * k, cx + IN * k, cy + IN * k)
        // up, right, down, left: the middle of each key, in degrees (Android measures from 3 o'clock, clockwise)
        for ((name, mid) in listOf("up" to 270f, "right" to 0f, "down" to 90f, "left" to 180f)) {
            seg.reset()
            seg.arcTo(outer, mid - HALF, HALF * 2, true)
            seg.arcTo(inner, mid + HALF, -HALF * 2)
            seg.close()
            c.drawPath(seg, fill)
            if (pressed == name) c.drawPath(seg, lit)
            c.drawPath(seg, edge)
            // the arrow, pointing outwards
            val r = (R + IN) / 2f * k
            val rad = Math.toRadians(mid.toDouble())
            val ax = cx + (r * Math.cos(rad)).toFloat(); val ay = cy + (r * Math.sin(rad)).toFloat()
            c.save(); c.translate(ax, ay); c.rotate(mid + 90f)
            val a = 7f * k
            c.drawLine(-a, a * 0.5f, 0f, -a * 0.5f, arrow); c.drawLine(0f, -a * 0.5f, a, a * 0.5f, arrow)
            c.restore()
        }
        c.drawCircle(cx, cy, OK * k, okFill)
        if (pressed == "ok") c.drawCircle(cx, cy, OK * k, lit)
        c.drawCircle(cx, cy, OK * k, okRing)
        c.drawText("OK", cx, cy - (okText.descent() + okText.ascent()) / 2f, okText)
    }

    private fun which(x: Float, y: Float): String? {
        val k = u(); val dx = x - width / 2f; val dy = y - height / 2f
        val r = hypot(dx, dy) / k
        if (r <= OK + 4f) return "ok"
        if (r > R + 18f) return null
        val deg = (Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360
        return when { deg >= 315 || deg < 45 -> "right"; deg < 135 -> "down"; deg < 225 -> "left"; else -> "up" }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = which(e.x, e.y) ?: return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                invalidate()
                if (p != null) onKey(p)
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; animate().scaleX(1f).scaleY(1f).setDuration(120).start(); invalidate() }
        }
        return true
    }
}
