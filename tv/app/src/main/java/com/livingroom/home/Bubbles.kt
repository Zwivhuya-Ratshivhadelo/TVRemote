package com.livingroom.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.view.View

/** One app as a round bubble: its real icon cut into a circle, with a glassy shine when selected. */
class BubbleView(context: Context) : View(context) {

    var art: Drawable? = null
    /** true = the icon fills the circle (square icons); false = a smaller icon on a dark circle */
    var fullBleed = true
    var bg = Color.rgb(28, 31, 42)
    var letter: String? = null
    var letterColor = Color.WHITE
    /** 0..1: how strongly the selected-app highlights show */
    var star = 0f
    /** Where the light sweep is (-0.3 .. 1.3 crosses the bubble; outside that it is hidden) */
    var sweep = -1f

    private val clip = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset(); clip.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width; val h = height
        canvas.save()
        canvas.clipPath(clip)
        p.shader = null; p.color = bg
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val a = art
        if (a is AdaptiveIconDrawable) {
            // Show the icon's own background and logo edge to edge (the middle two-thirds is the visible part).
            val pad = (w * 0.25f).toInt()
            a.background?.let { it.setBounds(-pad, -pad, w + pad, h + pad); it.draw(canvas) }
            a.foreground?.let { it.setBounds(-pad, -pad, w + pad, h + pad); it.draw(canvas) }
        } else if (a != null) {
            val inset = if (fullBleed) 0 else (w * 0.17f).toInt()
            a.setBounds(inset, inset, w - inset, h - inset); a.draw(canvas)
        }
        letter?.let {
            text.color = letterColor; text.textSize = w * (if (it.length > 1) 0.30f else 0.42f)
            canvas.drawText(it, w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text)
        }
        if (star > 0.01f) {
            // Glass shine, top left.
            p.shader = RadialGradient(w * 0.32f, h * 0.2f, w * 0.6f,
                intArrayOf(Color.argb((105 * star).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            // A band of light that sweeps across every few seconds.
            if (sweep > -0.3f && sweep < 1.3f) {
                val c = sweep * (w + h)
                p.shader = LinearGradient(c - w * 0.35f, 0f, c, h * 0.35f,
                    intArrayOf(Color.argb(0, 255, 255, 255), Color.argb((80 * star).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            p.shader = null
        }
        canvas.restore()
    }

    companion object {
        /** The app's icon at the highest quality it ships, so it stays sharp in a big bubble. */
        fun bigIcon(ctx: Context, pkg: String): Drawable? = try {
            val pm = ctx.packageManager
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            val res = pm.getResourcesForApplication(pkg)
            val id = launch?.component?.let { pm.getActivityInfo(it, 0).iconResource }?.takeIf { it != 0 }
                ?: pm.getApplicationInfo(pkg, 0).icon
            (if (id != 0) res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) else null) ?: pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            try { ctx.packageManager.getApplicationIcon(pkg) } catch (e2: Exception) { null }
        }

        /** Square icons fill the circle; icons with see-through corners sit smaller on a dark circle. */
        fun fillsCircle(d: Drawable): Boolean {
            if (d is AdaptiveIconDrawable) return true
            return try {
                val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 64, 64); d.draw(Canvas(b))
                Color.alpha(b.getPixel(3, 32)) > 200 && Color.alpha(b.getPixel(32, 3)) > 200
            } catch (e: Exception) { false }
        }
    }
}

/** The wide, soft shadow in the selected app's colour that spreads out behind it into the black. */
class HaloView(context: Context) : View(context) {
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        val c = color
        fun a(x: Float) = Color.argb((255 * x).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        p.shader = RadialGradient(width / 2f, height / 2f, r,
            intArrayOf(a(0.55f), a(0.30f), a(0.12f), a(0.04f), a(0f)),
            floatArrayOf(0f, 0.31f, 0.6f, 0.83f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(width / 2f, height / 2f, r, p)
    }

    companion object {
        /** Each app's own colour, or null for apps added from the phone (their colour comes from their icon). */
        fun knownColor(key: String): Int? = if (key in KNOWN) colorFor(key) else null
        private val KNOWN = setOf("netflix", "youtube", "prime", "dstv", "spotify", "sabc", "goojara", "chrome")

        /** Each app's own colour. */
        fun colorFor(key: String): Int = when (key) {
            "netflix" -> Color.rgb(0xE5, 0x09, 0x14)   // Netflix red #E50914
            "youtube" -> Color.rgb(0xFF, 0x00, 0x00)   // YouTube red #FF0000
            "prime" -> Color.rgb(0x00, 0xA8, 0xE1)     // Prime blue #00A8E1
            "dstv" -> Color.rgb(0x00, 0x6F, 0xB3)      // DStv blue #006FB3
            "spotify" -> Color.rgb(0x1E, 0xD7, 0x60)   // Spotify green #1ED760
            "sabc" -> Color.rgb(0x00, 0x60, 0xAF)      // SABC blue #0060AF
            "goojara" -> Color.rgb(0xF5, 0xC5, 0x18)
            "chrome" -> Color.rgb(0x42, 0x85, 0xF4)
            else -> Color.rgb(200, 210, 255)
        }
    }
}
