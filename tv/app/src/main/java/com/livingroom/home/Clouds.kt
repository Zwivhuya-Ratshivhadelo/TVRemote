package com.livingroom.home

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Real-looking clouds: a cloud texture made from layered noise (wraps left to right so it can drift
 * forever), then coloured with light and shadow for the time of day and the weather.
 * Worked out at 640 x 360 and drawn stretched to the screen (clouds are soft, so that looks right).
 */
object Clouds {
    const val W = 640
    const val H = 360

    /** Smooth random values on a grid that repeats left to right. */
    private class Grid(val fx: Int, val fy: Int, rnd: Random) {
        private val v = FloatArray(fx * (fy + 1)) { rnd.nextFloat() }
        fun at(x: Float, y: Float): Float {
            val xf = floor(x); val yf = floor(y).coerceIn(0f, (fy - 1).toFloat())
            val tx = x - xf; val ty = (y - yf).coerceIn(0f, 1f)
            val x0 = ((xf.toInt() % fx) + fx) % fx; val x1 = (x0 + 1) % fx
            val yi = yf.toInt()
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            val a = v[yi * fx + x0]; val b = v[yi * fx + x1]
            val c = v[(yi + 1) * fx + x0]; val d = v[(yi + 1) * fx + x1]
            val top = a + (b - a) * sx; val bot = c + (d - c) * sx
            return top + (bot - top) * sy
        }
    }

    /** Cloud thickness 0..1 for every point. Takes a moment, so it runs off the main thread. */
    fun density(seed: Long): FloatArray {
        val rnd = Random(seed)
        val octaves = (0 until 6).map { o ->
            // Same grid sizes as the mockups: 4, 6, 10, 19, 37, 72 rows; stretched wider than tall.
            val fy = (2.2f * 2f.pow(o)).toInt() + 2
            val fx = (2.2f * 2f.pow(o) * (16f / 9f) / 2f).toInt() + 2
            Grid(fx, fy, rnd)
        }
        val amps = FloatArray(6) { 0.48f.pow(it) }
        val warpX = Grid(8, 5, rnd); val warpY = Grid(8, 5, rnd)
        val out = FloatArray(W * H)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (y in 0 until H) {
            val yn = y / (H - 1f)
            for (x in 0 until W) {
                val xn = x / W.toFloat()
                // Push the texture around so the shapes billow instead of looking like static noise.
                var u = xn + (warpX.at(xn * 8f, yn * 5f) - 0.5f) * 0.094f
                u -= floor(u)
                val v = (yn + (warpY.at(xn * 8f, yn * 5f) - 0.5f) * 0.092f).coerceIn(0f, 1f)
                var s = 0f
                for (o in 0 until 6) { val g = octaves[o]; s += g.at(u * g.fx, v * g.fy) * amps[o] }
                out[y * W + x] = s
                lo = min(lo, s); hi = max(hi, s)
            }
        }
        val span = max(1e-6f, hi - lo)
        for (i in out.indices) out[i] = (out[i] - lo) / span
        return out
    }

    /** How a cloud layer looks: how much sky it covers, its colours, and where it gathers. */
    data class Look(
        val cover: Float, val soft: Float, val alpha: Float, val lit: Int, val dark: Int,
        val bandY: Float = 0.5f, val bandW: Float = 0.4f, val band: Float = 0f
    )

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }

    /** Colours the cloud texture: bright on the side facing the light, darker underneath. */
    fun paint(d: FloatArray, look: Look): Bitmap {
        val px = IntArray(W * H)
        val bandRow = FloatArray(H) { y -> val yn = y / (H - 1f); exp(-((yn - look.bandY) / look.bandW).let { it * it }) }
        fun dens(x: Int, y: Int): Float {
            val xx = ((x % W) + W) % W; val yy = y.coerceIn(0, H - 1)
            return d[yy * W + xx] * (1 - look.band) + look.band * bandRow[yy]
        }
        val e0 = 1 - look.cover; val e1 = e0 + look.soft
        val lr = Color.red(look.lit); val lg = Color.green(look.lit); val lb = Color.blue(look.lit)
        val dr = Color.red(look.dark); val dg = Color.green(look.dark); val db = Color.blue(look.dark)
        for (y in 0 until H) for (x in 0 until W) {
            val dd = dens(x, y)
            val a = smooth(e0, e1, dd) * look.alpha
            if (a < 0.004f) continue
            val toward = dens(x - 3, y + 3)               // light comes from the top right
            var shade = ((dd - toward) * 7f + 0.55f).coerceIn(0f, 1f)
            val body = ((dd - e0) / 0.35f).coerceIn(0f, 1f)
            shade = (shade * (1 - 0.45f * body) + 0.1f).coerceIn(0f, 1f)
            val r = (dr + (lr - dr) * shade).toInt(); val g = (dg + (lg - dg) * shade).toInt(); val b = (db + (lb - db) * shade).toInt()
            px[y * W + x] = Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)
        }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        return bmp
    }
}
