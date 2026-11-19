package com.livingroom.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * The live wallpaper: a realistic sky for the time of day, with the current weather on top
 * (sun, real drifting clouds, rain that gets heavier with the real rainfall, lightning bolts,
 * a deep blue night with stars and the moon glowing behind the clouds).
 */
class SkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    /** clear, partly cloudy, cloudy, rain, storm, fog */
    var kind: String = "clear"
        set(v) { field = v; rebuild() }
    var rainMm: Float = 0f
        set(v) { field = v; rebuild() }
    var hour: Float = 12f
        set(v) { field = v; rebuild() }
    /** false = plain dark background (Wallpaper button) */
    var live: Boolean = true
        set(v) { field = v; rebuild() }

    private val skyPaint = Paint()
    private val scrimPaint = Paint()
    private val shadePaint = Paint()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var running = false
    private var lastNanos = 0L
    private var t = 0f
    private var frame = 0
    private var flash = 0f
    private var nextFlash = 5f

    private var sunOp = 0f
    private var starOp = 0f
    private var moonOp = 0f
    private var dayness = 1f
    private var rainOn = false
    private var layerOp = floatArrayOf(0f, 0f, 0f)
    private var speed = 1f

    // Real clouds: two layers drifting at different speeds.
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var dens1: FloatArray? = null
    @Volatile private var dens2: FloatArray? = null
    private var cloud1: Bitmap? = null
    private var cloud2: Bitmap? = null
    private var cloudKey = ""
    private var drift1 = 0f
    private var drift2 = 0f
    private val dst = RectF()

    // Lightning bolt currently showing (in a storm).
    private val bolt = Path()
    private val branch = Path()
    private var boltLife = 0f
    private var boltX = 0f
    private var boltY = 0f

    private class Drop(var x: Float, var y: Float, val len: Float, val v: Float)
    private val layers = Array(3) { mutableListOf<Drop>() }
    private val stars = List(220) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.8f, Random.nextFloat() * 6.28f, 0.6f + Random.nextFloat() * 1.3f) }

    // [hour, top, middle, horizon]. Night is a deep blue, not black.
    private val sky = arrayOf(
        floatArrayOf(0f, 16f, 30f, 96f, 26f, 52f, 150f, 20f, 44f, 110f),
        floatArrayOf(4.5f, 16f, 30f, 96f, 26f, 52f, 150f, 20f, 44f, 110f),
        floatArrayOf(6f, 24f, 38f, 70f, 80f, 96f, 130f, 214f, 150f, 110f),
        floatArrayOf(7f, 56f, 92f, 148f, 140f, 170f, 205f, 238f, 196f, 156f),
        floatArrayOf(9f, 62f, 120f, 186f, 124f, 170f, 220f, 205f, 225f, 242f),
        floatArrayOf(13f, 44f, 108f, 180f, 104f, 162f, 222f, 190f, 220f, 244f),
        floatArrayOf(16.5f, 56f, 108f, 165f, 126f, 166f, 210f, 228f, 210f, 176f),
        floatArrayOf(18.3f, 38f, 58f, 100f, 150f, 110f, 90f, 240f, 160f, 90f),
        floatArrayOf(19.3f, 22f, 36f, 104f, 46f, 64f, 146f, 120f, 92f, 112f),
        floatArrayOf(20.5f, 16f, 30f, 96f, 26f, 52f, 150f, 20f, 44f, 110f),
        floatArrayOf(24f, 16f, 30f, 96f, 26f, 52f, 150f, 20f, 44f, 110f)
    )

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    private fun start() { if (!running) { running = true; lastNanos = 0L; Choreographer.getInstance().postFrameCallback(this) } }
    private fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (!WEATHER_SKY) { invalidate(); running = false; return } // nothing moves, so no need to keep redrawing
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = frameTimeNanos
        t += dt
        step(dt)
        // About 30 frames a second is plenty for a wallpaper and easy on the TV.
        if (frame++ % 2 == 0) invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrimPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(82, 7, 8, 20), Color.argb(0, 7, 8, 20), Color.argb(0, 7, 8, 20), Color.argb(178, 7, 8, 20)),
            floatArrayOf(0f, 0.3f, 0.55f, 1f), Shader.TileMode.CLAMP)
        seedRain()
        cloudKey = ""
        rebuild()
    }

    private fun seedRain() {
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()
        val spec = arrayOf(intArrayOf(130, 18, 900), intArrayOf(70, 30, 1250), intArrayOf(36, 44, 1800))
        for (i in 0..2) {
            layers[i].clear()
            repeat(spec[i][0]) {
                layers[i] += Drop(Random.nextFloat() * w * 1.2f - w * 0.1f, Random.nextFloat() * h,
                    spec[i][1] * (0.8f + Random.nextFloat() * 0.4f), spec[i][2] * (0.85f + Random.nextFloat() * 0.3f))
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    private fun rebuild() {
        if (width == 0) return
        if (!WEATHER_SKY) {
            // One fixed black background: no weather effects and no colour changes.
            skyPaint.shader = null
            skyPaint.color = Color.BLACK
            sunOp = 0f; starOp = 0f; moonOp = 0f; rainOn = false; dayness = 0f
            flash = 0f; boltLife = 0f; cloud1 = null; cloud2 = null; cloudKey = ""
            invalidate(); return
        }
        if (!live) {
            skyPaint.shader = null
            skyPaint.color = Color.rgb(7, 8, 20)
            sunOp = 0f; rainOn = false; starOp = 0.6f; moonOp = 0f
            cloud1 = null; cloud2 = null; cloudKey = ""
            invalidate(); return
        }
        val night = (if (hour < 4.5f || hour > 20.5f) 1f else if (hour < 6f) (6f - hour) / 1.5f
            else if (hour > 19.3f) (hour - 19.3f) / 1.2f else 0f).coerceIn(0f, 1f)
        dayness = 1f - night

        var a = sky[0]; var b = sky[1]
        for (i in 0 until sky.size - 1) if (hour >= sky[i][0] && hour <= sky[i + 1][0]) { a = sky[i]; b = sky[i + 1]; break }
        val f = if (b[0] == a[0]) 0f else (hour - a[0]) / (b[0] - a[0])
        // Bad weather greys and darkens the sky, a lot less at night so the night stays blue.
        val grey = when (kind) { "partly cloudy" -> 0.15f; "cloudy", "fog" -> 0.6f; "rain" -> 0.75f; "storm" -> 0.85f; else -> 0f } * (1f - 0.7f * night)
        var dim = when (kind) { "partly cloudy" -> 0.97f; "cloudy", "fog" -> 0.85f; "rain" -> 0.7f; "storm" -> 0.55f; else -> 1f }
        dim += (1f - dim) * 0.6f * night
        fun col(o: Int): Int {
            val r = a[o] + (b[o] - a[o]) * f
            val g = a[o + 1] + (b[o + 1] - a[o + 1]) * f
            val bl = a[o + 2] + (b[o + 2] - a[o + 2]) * f
            val avg = (r + g + bl) / 3f
            fun ch(v: Float) = ((v + (avg - v) * grey) * dim).toInt().coerceIn(0, 255)
            return Color.rgb(ch(r), ch(g), ch(bl))
        }
        skyPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(col(1), col(4), col(7)), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)

        // Text on the left stays readable when a bright cloud drifts behind it.
        val k = 0.42f * dayness
        shadePaint.shader = LinearGradient(0f, 0f, width * 0.42f, 0f,
            intArrayOf(Color.argb((255 * k).toInt(), 18, 32, 60), Color.argb((255 * k * 0.35f).toInt(), 18, 32, 60), Color.argb(0, 18, 32, 60)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

        val sunUp = hour in 6.2f..18.6f
        sunOp = when (kind) { "clear" -> if (sunUp) 1f else 0f; "partly cloudy" -> if (sunUp) 0.8f else 0f; else -> 0f }
        starOp = night * when (kind) { "clear", "partly cloudy" -> 1f; "cloudy" -> 0.9f; "fog" -> 0.2f; else -> 0.35f }
        moonOp = night * when (kind) { "clear", "partly cloudy", "cloudy" -> 1f; "fog" -> 0.4f; else -> 0f }

        rainOn = kind == "rain" || kind == "storm"
        val mm = if (kind == "storm") max(rainMm, 6f) else rainMm
        val heavy = ((mm - 0.2f) / 6f).coerceIn(0f, 1f)
        speed = 1f + 0.5f * heavy
        // Light rain is clearly visible; more millimetres add a second and third, heavier layer.
        layerOp = floatArrayOf(0.75f + 0.25f * heavy, 0.35f + 0.65f * ((mm - 0.3f) / 2f).coerceIn(0f, 1f), ((mm - 1.5f) / 3f).coerceIn(0f, 1f))

        updateClouds()
        invalidate()
    }

    // ------------------------------------------------------------------------------- clouds

    private fun updateClouds() {
        val day = (dayness * 10).toInt() / 10f
        // Sunrise and sunset light the clouds warm.
        val glowT = max(max(0f, 1f - abs(hour - 18.4f) / 1.1f), max(0f, 1f - abs(hour - 6.2f) / 0.9f))
        val warm = (glowT * 10).toInt() / 10f
        val key = "$kind|$day|$warm"
        if (key == cloudKey) return
        cloudKey = key

        var lit: Int; var dark: Int
        when (kind) {
            "storm" -> { lit = blend(rgb(92, 104, 150), rgb(150, 158, 175), day); dark = blend(rgb(14, 18, 40), rgb(60, 66, 80), day) }
            "rain" -> { lit = blend(rgb(120, 132, 180), rgb(200, 206, 218), day); dark = blend(rgb(24, 30, 64), rgb(92, 100, 118), day) }
            else -> { lit = blend(rgb(150, 165, 215), rgb(255, 255, 255), day); dark = blend(rgb(34, 44, 96), rgb(160, 174, 198), day) }
        }
        lit = blend(lit, rgb(255, 186, 140), 0.5f * warm); dark = blend(dark, rgb(120, 80, 90), 0.4f * warm)
        val look1: Clouds.Look?; val look2: Clouds.Look?
        when (kind) {
            "clear" -> { look1 = Clouds.Look(0.14f, 0.2f, 0.5f, lit, dark); look2 = null }
            "partly cloudy" -> { look1 = Clouds.Look(0.40f, 0.16f, 1f, lit, dark, 0.5f, 0.4f, 0.2f); look2 = Clouds.Look(0.22f, 0.16f, 0.7f, lit, dark) }
            "cloudy" -> { look1 = Clouds.Look(0.58f, 0.18f, 1f, lit, dark, 0.3f, 0.45f, 0.25f); look2 = Clouds.Look(0.34f, 0.16f, 0.82f, lit, dark, 0.7f, 0.35f, 0.2f) }
            "fog" -> { look1 = Clouds.Look(0.85f, 0.45f, 0.75f, lit, dark); look2 = null }
            "rain" -> { look1 = Clouds.Look(0.80f, 0.2f, 1f, lit, dark, 0.3f, 0.45f, 0.25f); look2 = Clouds.Look(0.45f, 0.18f, 0.75f, lit, dark) }
            else -> { look1 = Clouds.Look(0.90f, 0.2f, 1f, lit, dark, 0.35f, 0.4f, 0.3f); look2 = Clouds.Look(0.45f, 0.18f, 0.55f, lit, dark) }
        }
        worker.execute {
            try {
                val d1 = dens1 ?: Clouds.density(21L).also { dens1 = it }
                val d2 = dens2 ?: Clouds.density(22L).also { dens2 = it }
                val b1 = look1?.let { Clouds.paint(d1, it) }
                val b2 = look2?.let { Clouds.paint(d2, it) }
                post { if (cloudKey == key) { cloud1 = b1; cloud2 = b2; invalidate() } }
            } catch (e: Throwable) { }
        }
    }

    private fun drawLayer(canvas: Canvas, bmp: Bitmap?, drift: Float) {
        bmp ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val o = ((drift % w) + w) % w
        dst.set(-o, 0f, w - o, h); canvas.drawBitmap(bmp, null, dst, cloudPaint)
        dst.set(w - o, 0f, 2 * w - o, h); canvas.drawBitmap(bmp, null, dst, cloudPaint)
    }

    // ------------------------------------------------------------------------------- motion

    private fun step(dt: Float) {
        // Clouds drift slowly; the nearer layer a little faster. Storms move quicker.
        val wind = when (kind) { "storm" -> 2.2f; "rain" -> 1.6f; else -> 1f }
        drift1 += 7f * wind * dt
        drift2 += 13f * wind * dt
        if (rainOn) {
            val w = width.toFloat(); val h = height.toFloat()
            for (layer in layers) for (d in layer) {
                d.y += d.v * speed * dt
                d.x += d.v * speed * dt * TILT
                if (d.y - d.len > h) { d.y = -Random.nextFloat() * 120f; d.x = Random.nextFloat() * w * 1.2f - w * 0.2f }
            }
        }
        if (kind == "storm" && live) {
            nextFlash -= dt
            if (nextFlash <= 0f) { strike(); nextFlash = 5f + Random.nextFloat() * 8f }
            flash = max(0f, flash - dt * 3.5f)
            boltLife = max(0f, boltLife - dt)
        } else { flash = 0f; boltLife = 0f }
    }

    /** A jagged lightning bolt with one branch, somewhere across the right side of the sky. */
    private fun strike() {
        val w = width.toFloat(); val h = height.toFloat()
        boltX = w * (0.45f + Random.nextFloat() * 0.45f); boltY = h * 0.12f
        val end = h * (0.55f + Random.nextFloat() * 0.2f)
        bolt.reset(); branch.reset()
        var x = boltX; var y = boltY
        bolt.moveTo(x, y)
        val pts = mutableListOf<Pair<Float, Float>>()
        while (y < end) { y += 18f + Random.nextFloat() * 22f; x += -26f + Random.nextFloat() * 52f; bolt.lineTo(x, y); pts += x to y }
        if (pts.isNotEmpty()) {
            var (bx, by) = pts[pts.size / 2]
            branch.moveTo(bx, by)
            repeat(5) { bx += 10f + Random.nextFloat() * 24f; by += 16f + Random.nextFloat() * 14f; branch.lineTo(bx, by) }
        }
        boltLife = 0.38f
        flash = 0.7f
    }

    // ------------------------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        if (starOp > 0.01f) {
            paint.shader = null
            for (s in stars) {
                val tw = 0.55f + 0.45f * sin(t * 0.9f + s[2])
                paint.color = Color.argb((255 * starOp * tw * 0.85f).toInt().coerceIn(0, 255), 255, 255, 255)
                canvas.drawCircle(s[0] * w, s[1] * h, s[3], paint)
            }
        }

        if (moonOp > 0.01f) {
            // The moon glowing behind the clouds.
            paint.shader = RadialGradient(w * 0.77f, h * 0.2f, 380f,
                intArrayOf(Color.argb((115 * moonOp).toInt(), 190, 205, 255), Color.argb((30 * moonOp).toInt(), 190, 205, 255), Color.argb(0, 190, 205, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.77f, h * 0.2f, 380f, paint)
        }

        if (sunOp > 0.01f) {
            val dayT = ((hour - 6f) / 12.5f).coerceIn(0f, 1f)
            val cx = -60f + 1500f * dayT + 260f
            val cy = 560f - 520f * sin(PI.toFloat() * dayT) + 260f
            val pulse = 1f + 0.06f * sin(t * 0.8f)
            paint.shader = RadialGradient(cx, cy, 620f * pulse,
                intArrayOf(Color.argb((150 * sunOp).toInt(), 255, 236, 190), Color.argb((50 * sunOp).toInt(), 255, 214, 140), Color.argb(0, 255, 214, 140)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, 620f * pulse, paint)
            paint.shader = RadialGradient(cx - 10f, cy - 10f, 95f,
                intArrayOf(Color.argb((255 * sunOp).toInt(), 255, 251, 234), Color.argb((255 * sunOp).toInt(), 255, 240, 196), Color.argb(0, 255, 226, 150)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, 95f, paint)
        }
        paint.shader = null

        if (boltLife > 0f) {
            // The flash lights up the clouds from inside.
            val k = boltLife / 0.38f
            paint.shader = RadialGradient(boltX, boltY + 120f, 520f,
                intArrayOf(Color.argb((140 * k).toInt(), 190, 200, 255), Color.argb(0, 190, 200, 255)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(boltX, boltY + 120f, 520f, paint)
            paint.shader = null
        }

        drawLayer(canvas, cloud1, drift1)

        if (boltLife > 0f) {
            val k = boltLife / 0.38f
            boltPaint.strokeWidth = 24f; boltPaint.color = Color.argb((60 * k).toInt(), 140, 160, 255); canvas.drawPath(bolt, boltPaint)
            boltPaint.strokeWidth = 9f; boltPaint.color = Color.argb((140 * k).toInt(), 200, 210, 255); canvas.drawPath(bolt, boltPaint)
            boltPaint.strokeWidth = 3f; boltPaint.color = Color.argb((255 * k).toInt(), 255, 255, 255); canvas.drawPath(bolt, boltPaint)
            boltPaint.strokeWidth = 2f; boltPaint.color = Color.argb((220 * k).toInt(), 235, 240, 255); canvas.drawPath(branch, boltPaint)
        }

        drawLayer(canvas, cloud2, drift2)

        if (rainOn) {
            val widths = floatArrayOf(1.6f, 2.2f, 3.0f)
            val alphas = floatArrayOf(0.42f, 0.52f, 0.62f)
            for (i in 0..2) {
                val op = layerOp[i]
                if (op <= 0.01f) continue
                rainPaint.strokeWidth = widths[i]
                rainPaint.color = Color.argb((255 * op * alphas[i]).toInt().coerceIn(0, 255), 220, 232, 255)
                for (d in layers[i]) canvas.drawLine(d.x, d.y, d.x - d.len * TILT, d.y - d.len, rainPaint)
            }
        }

        if (flash > 0f) {
            paint.color = Color.argb((90 * flash).toInt(), 234, 240, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        if (dayness > 0.05f && live) canvas.drawRect(0f, 0f, w * 0.42f, h, shadePaint)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    companion object {
        /** false = plain fixed background (the weather panel still shows the weather). */
        const val WEATHER_SKY = false
        private val TILT = tan(8.0 * PI / 180.0).toFloat()
    }
}
