package com.livingroom.remote

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The remote: your TV apps floating with depth, or, while an app is open on the TV, what is playing. */
class RemoteActivity : Activity(), SensorEventListener {

    private lateinit var s: Scale
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var glow: Glow
    private lateinit var clock: BlockClock
    private lateinit var status: android.widget.TextView
    private lateinit var dot: View
    private lateinit var appsView: FrameLayout
    private lateinit var playView: FrameLayout
    private lateinit var gear: View
    private lateinit var grid: View

    // playing screen
    private lateinit var badgeIcon: Bubble
    private lateinit var badgeLabel: android.widget.TextView
    private lateinit var artImg: ImageView
    private lateinit var artBubble: Bubble
    private lateinit var title: android.widget.TextView
    private lateinit var artist: android.widget.TextView
    private lateinit var progress: Progress
    private lateinit var tStart: android.widget.TextView
    private lateinit var tEnd: android.widget.TextView
    private lateinit var playBtn: FrameLayout
    private lateinit var progressRow: List<View>

    // volume
    private lateinit var volBar: VolumeBar
    private lateinit var volNum: android.widget.TextView
    private var volLevel = -1

    private var forceApps = false
    private var lastApp: String? = null
    private var showingPlay = false
    private var glowColor = Color.rgb(200, 210, 255)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Scale(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        build()
    }

    // ------------------------------------------------------------------------------- layout

    private fun build() {
        glow = Glow(this)
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))

        clock = BlockClock(this).apply { step = s.px(7f); size = s.px(6f) }
        root.place(s, clock, 195f, 58f, BlockClock.COLS * 7f, 49f)
        status = text(s, 13f, alpha = 0.7f, spacing = 0.08f)
        root.place(s, status, 195f, 101f, 360f, 20f)
        dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) } }
        root.place(s, dot, 195f, 124f, 7f, 7f)

        appsView = FrameLayout(this).apply { clipChildren = false }
        root.addView(appsView, FrameLayout.LayoutParams(-1, -1))
        playView = FrameLayout(this).apply { alpha = 0f; visibility = View.GONE }
        root.addView(playView, FrameLayout.LayoutParams(-1, -1))
        buildPlay()

        gear = ImageView(this).apply { setImageResource(R.drawable.ic_gear); setPadding(s.px(10), s.px(10), s.px(10), s.px(10))
            setOnClickListener { tap(it); startActivity(Intent(this@RemoteActivity, SettingsActivity::class.java)) } }
        root.place(s, gear, 355f, 40f, 44f, 44f)
        grid = ImageView(this).apply { setImageResource(R.drawable.ic_grid); setPadding(s.px(10), s.px(10), s.px(10), s.px(10)); visibility = View.GONE
            setOnClickListener { tap(it); forceApps = true; refresh() } }
        root.place(s, grid, 36f, 40f, 44f, 44f)

        val row = listOf(
            R.drawable.ic_back to { _: View -> Hub.send("key", "k" to "back") },
            R.drawable.ic_home to { _: View -> Hub.send("key", "k" to "home") },
            R.drawable.ic_mic to { _: View -> startActivity(Intent(this, VoiceActivity::class.java)) },
            R.drawable.ic_keyboard to { _: View -> startActivity(Intent(this, TypeActivity::class.java)) },
            R.drawable.ic_mute to { _: View -> Hub.send("key", "k" to "mute") },
            R.drawable.ic_power to { _: View -> Hub.send("key", "k" to "power") })
        row.forEachIndexed { i, (ic, fn) -> root.place(s, roundButton(s, ic, 52f, iconSize = 22f, onClick = fn), 40f + i * 62f, 770f, 52f, 52f) }

        volBar = VolumeBar(this).apply { alpha = 0f }
        root.place(s, volBar, 368f, 400f, 44f, 200f)
        volNum = text(s, 13f, bold = true).apply { alpha = 0f }
        root.place(s, volNum, 368f, 518f, 50f, 20f)
    }

    private fun buildPlay() {
        val badge = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        badgeIcon = Bubble(this)
        badge.addView(badgeIcon, LinearLayout.LayoutParams(s.px(22), s.px(22)))
        badgeLabel = text(s, 14f, alpha = 0.85f)
        badge.addView(badgeLabel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = s.px(8) })
        playView.place(s, badge, 195f, 172f, 300f, 26f)

        artImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        playView.place(s, artImg, 195f, 320f, 250f, 250f)
        rounded(artImg, s.px(30f))
        artBubble = Bubble(this)
        playView.place(s, artBubble, 195f, 320f, 200f, 200f)

        title = text(s, 24f, bold = true); playView.place(s, title, 195f, 478f, 340f, 32f)
        artist = text(s, 15f, alpha = 0.7f); playView.place(s, artist, 195f, 508f, 340f, 22f)
        progress = Progress(this); playView.place(s, progress, 195f, 536f, 318f, 5f)
        tStart = text(s, 12f, alpha = 0.6f).apply { gravity = android.view.Gravity.START }; playView.place(s, tStart, 36f + 30f, 553f, 60f, 16f)
        tEnd = text(s, 12f, alpha = 0.6f).apply { gravity = android.view.Gravity.END }; playView.place(s, tEnd, 354f - 30f, 553f, 60f, 16f)
        progressRow = listOf(progress, tStart, tEnd)

        val media = listOf("prev" to R.drawable.ic_prev, "rew" to R.drawable.ic_rew10, "playpause" to R.drawable.ic_pause, "fwd" to R.drawable.ic_fwd10, "next" to R.drawable.ic_next)
        var x = 31f
        for ((k, ic) in media) {
            val white = k == "playpause"; val sz = if (white) 64f else 52f
            val btn = roundButton(s, ic, sz, white = white, iconSize = if (white) 26f else 22f) { Hub.send("key", "k" to k) }
            if (k == "rew" || k == "fwd") btn.addView(text(s, 8f, bold = true).apply { this.text = "10" }, FrameLayout.LayoutParams(-1, -1))
            playView.place(s, btn, x + sz / 2f, 607f, sz, sz)
            if (white) playBtn = btn
            x += sz + 14f
        }
    }

    // ------------------------------------------------------------------------------- floating apps

    private class B(val app: TvApp, val view: Bubble, val phase: Float) {
        var x = 0f; var y = 0f; var size = 0f; var alpha = 0f; var blur = 0f
        var fx = 0f; var fy = 0f; var fs = 0f; var fa = 0f; var fb = 0f
        var tx = 0f; var ty = 0f; var ts = 0f; var ta = 0f; var tb = 0f
        var applied = -1f; var incoming = false
    }

    private val bubbles = ArrayList<B>()
    private var appsKey = ""
    private var starId: String? = null
    private var moveStart = 0L
    private var clockT = 0f
    private val overshoot = OvershootInterpolator(1.3f)
    private val glide = DecelerateInterpolator(2f)

    private val spots = listOf(
        floatArrayOf(78f, 215f, .8f), floatArrayOf(318f, 195f, .5f), floatArrayOf(62f, 470f, .35f), floatArrayOf(334f, 430f, .9f),
        floatArrayOf(268f, 590f, .25f), floatArrayOf(96f, 620f, .45f), floatArrayOf(190f, 668f, .15f))

    private fun buildBubbles() {
        val list = Hub.apps.filter { it.on }.take(1 + spots.size)
        val key = list.joinToString("|") { it.id + (it.icon?.generationId ?: 0) }
        if (key == appsKey) return
        appsKey = key
        appsView.removeAllViews(); bubbles.clear()
        if (starId == null || list.none { it.id == starId }) starId = getPreferences(Context.MODE_PRIVATE).getString("star", null)?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        val big = s.px(190f).toInt()
        list.forEachIndexed { i, a ->
            val v = Bubble(this).apply { icon = a.icon; color = a.color; label = a.label; pivotX = big / 2f; pivotY = big / 2f }
            appsView.addView(v, FrameLayout.LayoutParams(big, big))
            val b = B(a, v, i * 1.7f)
            v.setOnClickListener { tap(it); choose(b) }
            bubbles += b
        }
        layoutTargets()
        for (b in bubbles) { b.x = b.tx; b.y = b.ty; b.size = b.ts; b.alpha = b.ta; b.blur = b.tb }
        applyBubbles()
    }

    /** Which spot at the back each app sits in (app id → spot). Kept while the app stays at the back. */
    private val spotOf = HashMap<String, Int>()

    private fun keepSpots() {
        val back = bubbles.filter { it.app.id != starId }
        val used = HashSet<Int>()
        for (b in back) { val sp = spotOf[b.app.id]; if (sp == null || sp !in back.indices || !used.add(sp)) spotOf.remove(b.app.id) }
        val free = back.indices.filter { it !in used }.toMutableList()
        for (b in back) if (spotOf[b.app.id] == null) spotOf[b.app.id] = free.removeAt(0)
    }

    private fun layoutTargets() {
        keepSpots()
        for (b in bubbles) {
            if (b.app.id == starId) { b.tx = 195f; b.ty = 370f; b.ts = 190f; b.ta = 1f; b.tb = 0f; b.view.translationZ = 2f }
            else { val sp = spots[spotOf[b.app.id] ?: 0]; b.tx = sp[0]; b.ty = sp[1]; b.ts = 50f + 56f * sp[2]; b.ta = .25f + .6f * sp[2]; b.tb = (1f - sp[2]) * 4f; b.view.translationZ = 0f }
        }
    }

    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (showingPlay || bubbles.size < 2 || kotlin.math.abs(vx) < kotlin.math.abs(vy) || kotlin.math.abs(vx) < 400) return false
                val i = bubbles.indexOfFirst { it.app.id == starId }.coerceAtLeast(0)
                val next = bubbles[(i + if (vx < 0) 1 else -1 + bubbles.size) % bubbles.size]
                tap(appsView); choose(next, open = false)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!showingPlay && swipe.onTouchEvent(ev) && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
            // A swipe, not a tap: let go of whatever the finger started on.
            val c = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(c); c.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Brings an app to the front (it floats forward, the one before drifts back) and, when tapped, opens it on the TV. */
    private fun choose(b: B, open: Boolean = true) {
        if (open) Hub.send("open", "id" to b.app.id)
        if (b.app.id == starId) { b.view.animate().scaleX(b.view.scaleX * 0.92f).scaleY(b.view.scaleY * 0.92f).setDuration(80).withEndAction { applyBubbles() }.start(); return }
        val old = bubbles.firstOrNull { it.app.id == starId }
        starId = b.app.id
        getPreferences(Context.MODE_PRIVATE).edit().putString("star", starId).apply()
        for (x in bubbles) { x.fx = x.x; x.fy = x.y; x.fs = x.size; x.fa = x.alpha; x.fb = x.blur; x.incoming = x === b }
        // Same as the TV: every app at the back moves to a new spot, so the whole cloud shifts around the one coming forward.
        val back = bubbles.filter { it !== b }
        var order = back.indices.shuffled()
        if (back.size > 1 && back.withIndex().all { (k, x) -> spotOf[x.app.id] == order[k] }) order = order.drop(1) + order.first()
        for ((k, x) in back.withIndex()) spotOf[x.app.id] = order[k]
        spotOf.remove(b.app.id)
        layoutTargets()
        // The new front app passes in front of everything on its way; the old one just behind it.
        old?.view?.translationZ = 1f
        moveStart = System.nanoTime()
        setGlow(b.app.color)
    }

    private val frame = object : Choreographer.FrameCallback {
        var last = 0L
        override fun doFrame(now: Long) {
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f); last = now
            clockT += dt
            if (moveStart != 0L) {
                val t = ((now - moveStart) / 1e9f / 0.65f).coerceIn(0f, 1f)
                for (b in bubbles) {
                    val e = if (b.incoming) overshoot.getInterpolation(t) else glide.getInterpolation(t); val g = glide.getInterpolation(t)
                    b.x = b.fx + (b.tx - b.fx) * g; b.y = b.fy + (b.ty - b.fy) * g; b.size = b.fs + (b.ts - b.fs) * e
                    b.alpha = b.fa + (b.ta - b.fa) * g; b.blur = b.fb + (b.tb - b.fb) * g
                }
                if (t >= 1f) moveStart = 0L
            }
            applyBubbles()
            if (showingPlay) updateProgress()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun applyBubbles() {
        val big = s.px(190f)
        for (b in bubbles) {
            val v = b.view
            val bobY = sin(clockT * 0.7f + b.phase) * (if (b.app.id == starId) 3f else 6f)
            val bobX = cos(clockT * 0.5f + b.phase) * 3f
            v.translationX = s.offX + s.px(b.x + bobX) - big / 2f
            v.translationY = s.px(b.y + bobY) - big / 2f
            val sc = s.px(b.size) / big
            v.scaleX = sc; v.scaleY = sc; v.alpha = b.alpha
            if (Build.VERSION.SDK_INT >= 31 && kotlin.math.abs(b.blur - b.applied) > 0.2f) {
                b.applied = b.blur
                val r = s.px(b.blur) / sc.coerceAtLeast(0.1f)
                v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
            }
        }
    }

    // ------------------------------------------------------------------------------- updates from the TV

    private val onHub = { refresh() }

    private fun refresh() {
        val st = Hub.state
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (Hub.linked && Hub.tvOnline) Color.rgb(30, 215, 96) else Color.rgb(85, 85, 85)) }
        status.text = buildString {
            append("LIVING ROOM")
            if (st?.temp != null && !st.wx.isNullOrBlank()) append(" · ${st.temp}° ${st.wx!!.uppercase()}")
        }
        buildBubbles()

        if (st?.app != lastApp) { lastApp = st?.app; forceApps = false }
        val play = st?.app != null && !forceApps
        if (play != showingPlay) switchTo(play)
        grid.visibility = if (play) View.VISIBLE else View.GONE
        gear.visibility = if (play) View.GONE else View.VISIBLE

        if (play && st != null) {
            val app = Hub.apps.firstOrNull { it.id == st.app }
            badgeIcon.icon = app?.icon; badgeIcon.color = app?.color ?: Color.DKGRAY; badgeIcon.label = st.appLabel
            badgeLabel.text = st.appLabel
            val m = st.media
            val art = Hub.art?.takeIf { m != null && it.first == m.art }?.second
            if (art != null) { artImg.setImageBitmap(art); artImg.visibility = View.VISIBLE; artBubble.visibility = View.GONE }
            else { artImg.visibility = View.GONE; artBubble.visibility = View.VISIBLE; artBubble.icon = app?.icon; artBubble.color = app?.color ?: Color.DKGRAY; artBubble.label = st.appLabel }
            title.text = m?.title?.takeIf { it.isNotBlank() } ?: st.appLabel
            artist.text = m?.artist ?: ""
            for (v in progressRow) v.visibility = if (m != null && m.dur > 0) View.VISIBLE else View.INVISIBLE
            (playBtn.findViewWithTag<ImageView>("icon"))?.setImageResource(if (m?.playing == false) R.drawable.ic_play else R.drawable.ic_pause)
            setGlow(art?.let { averageColor(it) } ?: app?.color ?: Color.rgb(200, 210, 255))
            updateProgress()
        } else {
            bubbles.firstOrNull { it.app.id == starId }?.let { setGlow(it.app.color) }
        }
        if (st != null && volLevel < 0) volLevel = st.vol
    }

    private fun updateProgress() {
        val m = Hub.state?.media ?: return
        if (m.dur <= 0) return
        val pos = if (m.playing) m.pos + (System.currentTimeMillis() - m.at) else m.pos
        val p = pos.coerceIn(0, m.dur)
        progress.frac = p.toFloat() / m.dur
        tStart.text = fmt(p); tEnd.text = fmt(m.dur)
    }

    private fun switchTo(play: Boolean) {
        showingPlay = play
        val show = if (play) playView else appsView
        val hide = if (play) appsView else playView
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).setDuration(260).start()
        hide.animate().alpha(0f).setDuration(200).withEndAction { if (showingPlay == play) hide.visibility = View.GONE }.start()
        glow.cy = if (play) 320f / 844f else 370f / 844f
    }

    private var glowAnim: ValueAnimator? = null
    private fun setGlow(c: Int) {
        if (c == glowColor) return
        glowAnim?.cancel()
        val from = glowColor; glowColor = c
        glowAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, c).setDuration(500).apply { addUpdateListener { glow.color = it.animatedValue as Int }; start() }
    }

    // ------------------------------------------------------------------------------- phone volume buttons and shake

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
            Hub.send("key", "k" to if (up) "vol_up" else "vol_down")
            val max = Hub.state?.volMax ?: 15
            if (volLevel < 0) volLevel = Hub.state?.vol ?: 0
            volLevel = (volLevel + if (up) 1 else -1).coerceIn(0, max)
            showVolume(volLevel, max)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.onKeyUp(keyCode, event)

    private val hideVol = Runnable { volBar.animate().alpha(0f).setDuration(250).start(); volNum.animate().alpha(0f).setDuration(250).start(); volLevel = -1 }
    private fun showVolume(level: Int, max: Int) {
        volBar.frac = level.toFloat() / maxOf(1, max)
        volNum.text = "$level"
        volBar.animate().alpha(1f).setDuration(120).start(); volNum.animate().alpha(1f).setDuration(120).start()
        main.removeCallbacks(hideVol); main.postDelayed(hideVol, 1500)
    }

    private var sensors: SensorManager? = null
    private var peaks = 0; private var firstPeak = 0L; private var lastShake = 0L
    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.6f) {
            if (now - firstPeak > 600) { firstPeak = now; peaks = 0 }
            peaks++
            if (peaks >= 2 && now - lastShake > 1500) { lastShake = now; peaks = 0; tap(root); Hub.send("key", "k" to "playpause") }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------------------- lifecycle

    private val tick = object : Runnable {
        override fun run() { clock.set(SimpleDateFormat("HH:mm", Locale.UK).format(Date())); main.postDelayed(this, 1000) }
    }

    override fun onStart() {
        super.onStart()
        Hub.start(this); Hub.listen(onHub)
        Hub.send("state_get")
        refresh()
        main.post(tick)
        frame.last = 0L; Choreographer.getInstance().postFrameCallback(frame)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        checkUpdates()
    }

    /** New builds from GitHub: the phone app, at most every 6 hours; and a nudge for the TV to look too. */
    private val updater by lazy { Updater(applicationContext, "phone").also { it.start() } }
    private fun checkUpdates() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("update_at", 0L) < 6 * 60 * 60_000L) return
        prefs.edit().putLong("update_at", now).apply()
        Hub.send("update_check")
        updater.check { f -> runOnUiThread { updater.install(f) } }
    }

    override fun onStop() {
        sensors?.unregisterListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(tick)
        Hub.unlisten(onHub); Hub.stop()
        super.onStop()
    }
}
