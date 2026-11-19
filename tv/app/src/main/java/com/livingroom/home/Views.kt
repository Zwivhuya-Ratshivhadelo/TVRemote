package com.livingroom.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Transparent analog clock: thin white ring, white hands, orange second hand. */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var hour = 0
    private var minute = 0
    private var second = 0

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(217, 255, 255, 255); strokeWidth = 2f
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND
    }
    private val hourHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val minuteHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND
    }
    private val secondHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFF5A36.toInt(); strokeWidth = 1.2f; strokeCap = Paint.Cap.ROUND
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A36.toInt() }

    fun setTime(h: Int, m: Int, s: Int) {
        if (h == hour && m == minute && s == second) return
        hour = h; minute = m; second = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val k = min(width, height) / 100f
        canvas.save()
        canvas.scale(k, k)
        canvas.drawCircle(50f, 50f, 47f, ring)
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0
            val sx = sin(a).toFloat(); val cy = cos(a).toFloat()
            canvas.drawLine(50f + 38f * sx, 50f - 38f * cy, 50f + 44f * sx, 50f - 44f * cy, tick)
        }
        canvas.save(); canvas.rotate((hour % 12) * 30f + minute * 0.5f, 50f, 50f); canvas.drawLine(50f, 50f, 50f, 27f, hourHand); canvas.restore()
        canvas.save(); canvas.rotate(minute * 6f + second * 0.1f, 50f, 50f); canvas.drawLine(50f, 50f, 50f, 14f, minuteHand); canvas.restore()
        canvas.save(); canvas.rotate(second * 6f, 50f, 50f); canvas.drawLine(50f, 58f, 50f, 12f, secondHand); canvas.restore()
        canvas.drawCircle(50f, 50f, 3f, hub)
        canvas.restore()
    }
}

/** The time made of white squares (like a pixel / LED clock). The colon blinks every second. */
class PixelClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var text = "00:00"
    private var colonOn = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()

    private val font = mapOf(
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        ':' to arrayOf("0", "0", "1", "0", "1", "0", "0")
    )

    // Animation B ("Drop"): when a digit changes, its old blocks fall away and the new blocks drop in and stack with a small bounce.
    private var prev = text
    private var changeAt = 0L
    private val animMs = 1700L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Let the blocks fall in from above the clock without being cut off.
        (parent as? android.view.ViewGroup)?.clipChildren = false
        (parent?.parent as? android.view.ViewGroup)?.clipChildren = false
    }

    fun setTime(h: Int, m: Int, s: Int) {
        val t = "%02d:%02d".format(h, m)
        val c = s % 2 == 0
        if (t == text && c == colonOn) return
        if (t != text) { prev = text; changeAt = android.os.SystemClock.uptimeMillis() }
        text = t; colonOn = c
        invalidate()
    }

    private fun bit(g: Array<String>?, row: Int, c: Int) = g != null && c < g[row].length && g[row][c] == '1'

    override fun onDraw(canvas: Canvas) {
        val step = 13f
        val size = 11f
        val k = step / 18f
        val elapsed = android.os.SystemClock.uptimeMillis() - changeAt
        val animating = prev != text && elapsed < animMs
        var col = 0
        for ((i, ch) in text.withIndex()) {
            val g = font[ch] ?: continue
            val old = font[prev.getOrNull(i) ?: ch]
            val w = g[0].length
            for (c in 0 until w) for (row in 0 until 7) {
                val x = (col + c) * step
                val y0 = row * step
                if (ch == ':') {
                    if (bit(g, row, c) && colonOn) { r.set(x, y0, x + size, y0 + size); paint.alpha = 255; canvas.drawRoundRect(r, 2.6f, 2.6f, paint) }
                    continue
                }
                val on = bit(g, row, c)
                val was = if (animating) bit(old, row, c) else on
                var dy = 0f
                var a = 1f
                if (on && !was) {
                    // New block: drops in from above, bottom rows first, lands with a little bounce.
                    val t = elapsed - (300 + (6 - row) * 70 + c * 20)
                    if (t < 0) continue
                    val p = t / 700f
                    dy = when {
                        p < 0.75f -> -320f * k * (1f - (p / 0.75f) * (p / 0.75f))
                        p < 1f -> -14f * k * kotlin.math.sin(Math.PI.toFloat() * (p - 0.75f) / 0.25f)
                        else -> 0f
                    }
                    a = (t / 120f).coerceAtMost(1f)
                } else if (!on && was) {
                    // Old block: falls away and fades.
                    val t = elapsed - (6 - row) * 25
                    if (t >= 0) {
                        val p = t / 500f
                        if (p >= 1f) continue
                        dy = 260f * k * p * p; a = 1f - p
                    }
                } else if (!on) continue
                r.set(x, y0 + dy, x + size, y0 + dy + size)
                paint.alpha = (255 * a).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(r, 2.6f, 2.6f, paint)
            }
            col += w + 1
        }
        paint.alpha = 255
        if (animating) postInvalidateOnAnimation() else prev = text
    }
}

/** Colour weather icons drawn in code, so they stay sharp at any size. Drawn on a 100 x 100 grid. */
class WeatherIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var kind: String = "cloud"
        set(v) { if (v != field) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun circle(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r, Path.Direction.CW) }

    private val cloudShape: Path = Path().apply {
        addRoundRect(RectF(14f, 56f, 88f, 78f), 11f, 11f, Path.Direction.CW)
        op(circle(33f, 58f, 16f), Path.Op.UNION)
        op(circle(54f, 46f, 21f), Path.Op.UNION)
        op(circle(74f, 58f, 14f), Path.Op.UNION)
    }

    private val moonShape: Path = circle(47f, 52f, 29f).apply { op(circle(63f, 39f, 24f), Path.Op.DIFFERENCE) }

    private val bolt: Path = Path().apply {
        moveTo(54f, 64f); lineTo(42f, 82f); lineTo(52f, 82f); lineTo(46f, 96f); lineTo(63f, 75f); lineTo(53f, 75f); lineTo(59f, 64f); close()
    }

    private fun drop(x: Float, y: Float) = Path().apply {
        moveTo(x, y); lineTo(x - 4.6f, y + 10f); lineTo(x + 4.6f, y + 10f); close()
        op(circle(x, y + 11f, 5f), Path.Op.UNION)
    }

    private val drops: Path = drop(38f, 79f).apply { op(drop(54f, 85f), Path.Op.UNION); op(drop(70f, 79f), Path.Op.UNION) }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x, y, r * 1.9f, intArrayOf(0x8CFFD25A.toInt(), 0x00FFD25A), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 1.9f, p)
        p.shader = RadialGradient(x - r * 0.15f, y - r * 0.25f, r * 1.25f,
            intArrayOf(0xFFFFF3B0.toInt(), 0xFFFFC94A.toInt(), 0xFFFFA631.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    private fun cloud(c: Canvas, dx: Float, dy: Float, top: Int, bottom: Int) {
        c.save(); c.translate(dx, dy)
        p.shader = LinearGradient(0f, 25f, 0f, 78f, top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(cloudShape, p)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100f * s) / 2f, (height - 100f * s) / 2f)
        canvas.scale(s, s)
        val white = 0xFFFFFFFF.toInt(); val soft = 0xFFD3DCE8.toInt()
        when (kind) {
            "sun" -> sun(canvas, 50f, 50f, 24f)
            "moon" -> {
                p.shader = LinearGradient(20f, 20f, 76f, 80f, 0xFFFFF8E4.toInt(), 0xFFE8D9B0.toInt(), Shader.TileMode.CLAMP)
                canvas.drawPath(moonShape, p)
            }
            "partly" -> { sun(canvas, 38f, 38f, 18f); cloud(canvas, 4f, 6f, white, soft) }
            "rain" -> {
                cloud(canvas, 0f, -12f, white, soft)
                p.shader = null; p.color = 0xFF6EC3FF.toInt(); canvas.drawPath(drops, p)
            }
            "storm" -> {
                cloud(canvas, 0f, -12f, 0xFFC9D2DE.toInt(), 0xFF8E99AA.toInt())
                p.shader = null; p.color = 0xFFFFD23F.toInt(); canvas.drawPath(bolt, p)
            }
            "fog" -> {
                p.shader = null; p.color = 0xD9FFFFFF.toInt()
                for ((i, y) in listOf(36f, 50f, 64f).withIndex()) {
                    val inset = if (i == 1) 10f else 18f
                    canvas.drawRoundRect(RectF(inset, y - 4.5f, 100f - inset, y + 4.5f), 4.5f, 4.5f, p)
                }
            }
            else -> cloud(canvas, 0f, 0f, white, soft)
        }
        p.shader = null
        canvas.restore()
    }
}

fun withAlpha(rgb: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0x00FFFFFF)

fun blend(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b))
    )
}
