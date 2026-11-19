package com.livingroom.remote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Sizes on the screens come from a 390 x 844 design and are scaled to the phone. */
class Scale(a: Activity) {
    private val dm = a.resources.displayMetrics
    val u: Float = minOf(dm.widthPixels / 390f, dm.heightPixels / 844f)
    val offX: Float = (dm.widthPixels - 390f * u) / 2f
    fun px(v: Float) = v * u
    fun px(v: Int) = (v * u).toInt()
}

/** Puts a view with its centre at (cx, cy) in design units. */
fun FrameLayout.place(s: Scale, v: View, cx: Float, cy: Float, w: Float, h: Float) {
    addView(v, FrameLayout.LayoutParams(s.px(w).toInt(), s.px(h).toInt()))
    v.translationX = s.offX + s.px(cx - w / 2f)
    v.translationY = s.px(cy - h / 2f)
}

fun Context.text(s: Scale, sizeDesign: Float, bold: Boolean = false, alpha: Float = 1f, spacing: Float = 0f) = TextView(this).apply {
    setTextColor(Color.WHITE); this.alpha = alpha
    setTextSize(TypedValue.COMPLEX_UNIT_PX, s.px(sizeDesign))
    typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    letterSpacing = spacing
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
}

fun tap(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

fun roundBg(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

/** Dark metal key, the look of every button. */
fun metal(s: Scale, red: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(0x34, 0x36, 0x3d), Color.rgb(0x18, 0x19, 0x1d))).apply {
    shape = GradientDrawable.OVAL; setStroke(maxOf(1, s.px(1)), if (red) Color.argb(102, 255, 90, 99) else Color.argb(26, 255, 255, 255))
}

/** The main key (home, play/pause, mic): black, a thin light ring, and a dark collar around it. */
fun ringKey(s: Scale) = android.graphics.drawable.LayerDrawable(arrayOf(
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0x1d, 0x1e, 0x22)) },
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0x0c, 0x0d, 0x0f)); setStroke(maxOf(1, s.px(1.5f).toInt()), Color.argb(90, 255, 255, 255)) })).apply {
    val inset = s.px(6); setLayerInset(1, inset, inset, inset, inset)
}

/** A round button with an icon. big = the main one (home, play/pause). */
fun Context.roundButton(s: Scale, icon: Int, size: Float, white: Boolean = false, iconSize: Float = 24f, red: Boolean = false, onClick: (View) -> Unit): FrameLayout {
    val f = FrameLayout(this)
    f.background = if (white) ringKey(s) else metal(s, red)
    val img = ImageView(this).apply { setImageResource(icon); tag = "icon" }
    f.addView(img, FrameLayout.LayoutParams(s.px(iconSize).toInt(), s.px(iconSize).toInt(), Gravity.CENTER))
    f.isClickable = true
    f.setOnClickListener { tap(it); it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start(); onClick(it) }
    return f
}

/** An app as a round bubble: the TV's own icon for it, or its first letter. */
class Bubble(ctx: Context) : View(ctx) {
    var icon: Bitmap? = null
        set(v) { field = v; invalidate() }
    var color: Int = Color.DKGRAY
    var label: String = ""
    private val clip = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { clip.reset(); clip.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW) }
    override fun onDraw(c: Canvas) {
        c.save(); c.clipPath(clip)
        val b = icon
        if (b != null) { dst.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawBitmap(b, null, dst, p) }
        else {
            p.color = color; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = width * 0.4f
            c.drawText(label.take(1), width / 2f, height / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
        c.restore()
    }
}

/** The soft coloured light behind everything, in the colour of what is selected or playing. */
class Glow(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var cx = 0.5f; var cy = 0.44f
    var color: Int = Color.rgb(200, 210, 255)
        set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = width * 1.15f
        val x = width * cx; val y = height * cy
        fun a(f: Float) = Color.argb((255 * f).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        p.shader = RadialGradient(x, y, r, intArrayOf(a(0.40f), a(0.17f), a(0.05f), a(0f)), floatArrayOf(0f, 0.28f, 0.5f, 0.66f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** The block clock, same as the TV's: when a minute changes, the old blocks drop away and the new ones drop in. */
class BlockClock(ctx: Context) : View(ctx) {
    var step = 7f; var size = 6f
    private var text = "00:00"; private var prev = text; private var changeAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()
    fun set(t: String) { if (t != text) { prev = text; text = t; changeAt = SystemClock.uptimeMillis(); invalidate() } }
    override fun onDraw(c: Canvas) {
        val e = SystemClock.uptimeMillis() - changeAt
        val anim = prev != text && e < 1700
        var col = 0
        val k = step / 18f
        for ((i, ch) in text.withIndex()) {
            if (ch == ':') {
                for (row in intArrayOf(2, 4)) { r.set(col * step, row * step, col * step + size, row * step + size); p.alpha = 255; c.drawRoundRect(r, size * .23f, size * .23f, p) }
                col += 2; continue
            }
            val g = FONT[ch] ?: continue
            val o = FONT[prev.getOrNull(i) ?: ch]
            for (cc in 0 until 5) for (row in 0 until 7) {
                val on = g[row][cc] == '1'
                val was = if (anim) o?.get(row)?.get(cc) == '1' else on
                var dy = 0f; var a = 1f
                if (on && !was) {
                    val t = e - (300 + (6 - row) * 70 + cc * 20); if (t < 0) continue
                    val q = t / 700f
                    dy = when { q < .75f -> -320f * k * (1 - (q / .75f) * (q / .75f)); q < 1f -> -14f * k * kotlin.math.sin(Math.PI.toFloat() * (q - .75f) / .25f); else -> 0f }
                    a = (t / 120f).coerceAtMost(1f)
                } else if (!on && was) {
                    val t = e - (6 - row) * 25
                    if (t >= 0) { val q = t / 500f; if (q >= 1f) continue; dy = 260f * k * q * q; a = 1 - q }
                } else if (!on) continue
                val x = (col + cc) * step; val y = row * step + dy
                r.set(x, y, x + size, y + size); p.alpha = (255 * a).toInt().coerceIn(0, 255)
                c.drawRoundRect(r, size * .23f, size * .23f, p)
            }
            col += 6
        }
        p.alpha = 255
        if (anim) postInvalidateOnAnimation() else prev = text
    }

    companion object {
        private val FONT = mapOf(
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"), '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"), '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"), '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
            '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"), '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"), '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"))
        /** Width in blocks of "HH:MM". */
        const val COLS = 26
    }
}

/** Thin progress line for the song or video. */
class Progress(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.color = Color.argb(38, 255, 255, 255); c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
        p.color = Color.WHITE; c.drawRoundRect(0f, 0f, width * frac, h, r, r, p)
    }
}

/** The TV's volume level, shown for a moment while you press your phone's volume buttons. */
class VolumeBar(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = w / 2
        path.reset(); path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.save(); c.clipPath(path)
        p.color = Color.argb(31, 255, 255, 255); c.drawRect(0f, 0f, w, h, p)
        p.color = Color.WHITE; c.drawRect(0f, h * (1 - frac), w, h, p)
        c.restore()
    }
}

fun rounded(v: View, radius: Float) {
    v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, radius) } }
    v.clipToOutline = true
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Average colour of a picture, leaning to its colourful parts (for the glow). */
fun averageColor(b: Bitmap): Int {
    val sm = Bitmap.createScaledBitmap(b, 24, 24, true)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0L
    for (y in 0 until 24) for (x in 0 until 24) {
        val p = sm.getPixel(x, y)
        if (Color.alpha(p) < 200) continue
        val w = 1 + (maxOf(Color.red(p), Color.green(p), Color.blue(p)) - minOf(Color.red(p), Color.green(p), Color.blue(p))) / 16
        r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; n += w
    }
    return if (n == 0L) Color.rgb(200, 210, 255) else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
}

/** Three dots that pulse one after another, while waiting for the TV. */
class Loader(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val r = height * 0.22f; val gap = r * 3.2f; val cx = width / 2f; val cy = height / 2f
        for (i in 0 until 3) {
            val k = ((kotlin.math.sin((t * 5f - i * 0.9f).toDouble()) + 1) / 2).toFloat()
            p.alpha = (70 + 185 * k).toInt()
            c.drawCircle(cx + (i - 1) * gap, cy - k * r * 0.6f, r * (0.8f + 0.2f * k), p)
        }
        postInvalidateOnAnimation()
    }
}
