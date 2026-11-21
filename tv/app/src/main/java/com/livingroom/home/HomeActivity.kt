package com.livingroom.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HomeActivity : Activity() {

    companion object {
        const val BUB = 340
        const val HALO = 1100
        const val MOVE_SECONDS = 0.75f
        // The selected app always comes forward here (right side, lower middle).
        const val STAR_X = 1500f
        const val STAR_Y = 700f
        // Back spots, scattered on purpose: x, y, depth (1 = near, 0 = far).
        val BACK = arrayOf(
            floatArrayOf(1700f, 470f, 0.90f), floatArrayOf(1010f, 840f, 0.15f), floatArrayOf(1200f, 440f, 0.60f),
            floatArrayOf(1850f, 900f, 0.10f), floatArrayOf(1270f, 955f, 0.40f), floatArrayOf(900f, 610f, 0.25f),
            floatArrayOf(1600f, 250f, 0.70f)
        )
        /** True while our home screen is the one on the TV. */
        @Volatile @JvmStatic var showing = false
        const val TILE_GAP = 24; const val PICK_W = 384; const val PICK_H = 216; const val PICK_GAP = 24 }

    private enum class Zone { DOCK, PICKS, PANEL, INPUTS }

    private class Pill(val name: String, val icon: Int, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var sky: SkyView
    private lateinit var greeting: TextView
    private lateinit var pixelClock: PixelClockView
    private lateinit var analogClock: AnalogClockView
    private lateinit var date: TextView
    private lateinit var wxIcon: WeatherIconView
    private lateinit var wxHighLow: TextView
    private lateinit var wxTemp: TextView
    private lateinit var wxLabel: TextView
    private lateinit var wxLine1: TextView
    private lateinit var wxLine2: TextView
    private lateinit var forecast: LinearLayout
    private lateinit var pillsBar: LinearLayout
    private lateinit var picks: LinearLayout
    private lateinit var picksTitle: TextView
    private lateinit var picksRow: LinearLayout
    private lateinit var dock: FrameLayout
    private lateinit var wxWarnRow: LinearLayout
    private lateinit var wxWarnIcon: WeatherIconView
    private lateinit var wxWarn: TextView
    private lateinit var inputsPanel: FrameLayout
    private lateinit var inputsList: LinearLayout
    private lateinit var toast: TextView

    private var zone = Zone.DOCK
    private var entries: List<Entry> = emptyList()
    private var sel = 0
    private var pillSel = 0
    private var pickSel = 0
    private var pills: List<Pill> = emptyList()
    private var netflixPicks: List<Pick> = emptyList()
    private var youtubePicks: List<Pick> = emptyList()
    private var shownPicks: List<Pick> = emptyList()
    private var shownPicksKey: String? = null
    private var online = false
    private var wifiLabel = "Wi-Fi"
    private var lastMinute = -1
    private var inputs: List<Pair<String, TvInputInfo>> = emptyList()
    private var inputSel = 0
    private val consumedDown = HashSet<Int>()

    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.UK)

    // ------------------------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bind()
        try {
            val f = IntentFilter(RemoteLink.HOME_LIST_CHANGED).apply { addAction(RemoteLink.DPAD); addAction(RemoteLink.SELECT) }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(homeListChanged, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(homeListChanged, f)
        } catch (e: Exception) { }
        fitStageToScreen()
        sky.live = getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("live_sky", true)
        sky.scaleX = 1.06f; sky.scaleY = 1.06f
        buildPills()
        val cachedWeather = Weather.cached(this)
        if (cachedWeather != null) showWeather(cachedWeather) else sky.kind = "clear"
        youtubePicks = Feeds.youtubeCached(this)
        watchConnectivity()
        Skeem.schedule(this)
        setZone(Zone.DOCK)
    }

    override fun onResume() {
        super.onResume()
        showing = true
        HomeKeyService.instance?.homeShowing()
        entries.getOrNull(sel)?.let { HomeKeyService.instance?.homeSelected(HomeList.idOf(it)) }
        main.removeCallbacks(ticker)
        ticker.run()
        refreshApps()
        refreshNetflix()
        askListPermissionOnce()
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, 10 * 60_000L)
        bubbleFrame.last = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(bubbleFrame)
        android.view.Choreographer.getInstance().postFrameCallback(bubbleFrame)
        dock.alpha = 0f
        dock.translationY = 40f
        dock.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    override fun onPause() {
        showing = false
        android.view.Choreographer.getInstance().removeFrameCallback(bubbleFrame)
        main.removeCallbacks(ticker)
        main.removeCallbacks(periodic)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(homeListChanged) } catch (e: Exception) { }
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) } catch (e: Exception) { }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setZone(Zone.DOCK)
    }

    @Deprecated("Back on the home screen only closes what is open")
    override fun onBackPressed() {
        if (zone != Zone.DOCK) setZone(Zone.DOCK)
    }

    private fun bind() {
        root = findViewById(R.id.root); stage = findViewById(R.id.stage); sky = findViewById(R.id.sky)
        greeting = findViewById(R.id.greeting); pixelClock = findViewById(R.id.pixelClock)
        analogClock = findViewById(R.id.analogClock); date = findViewById(R.id.date)
        wxIcon = findViewById(R.id.wxIcon); wxTemp = findViewById(R.id.wxTemp); wxLabel = findViewById(R.id.wxLabel)
        wxHighLow = findViewById(R.id.wxHighLow)
        wxWarnRow = findViewById(R.id.wxWarnRow); wxWarnIcon = findViewById(R.id.wxWarnIcon); wxWarn = findViewById(R.id.wxWarn)
        wxLine1 = findViewById(R.id.wxLine1); wxLine2 = findViewById(R.id.wxLine2); forecast = findViewById(R.id.forecast)
        pillsBar = findViewById(R.id.pills); picks = findViewById(R.id.picks); picksTitle = findViewById(R.id.picksTitle)
        picksRow = findViewById(R.id.picksRow); dock = findViewById(R.id.dock)
        inputsPanel = findViewById(R.id.inputsPanel); inputsList = findViewById(R.id.inputsList); toast = findViewById(R.id.toast)
    }

    private fun fitStageToScreen() {
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width.toFloat(); val h = v.height.toFloat()
            if (w <= 0f || h <= 0f) return@addOnLayoutChangeListener
            val s = minOf(w / 1920f, h / 1080f)
            stage.pivotX = 0f; stage.pivotY = 0f
            stage.scaleX = s; stage.scaleY = s
            stage.translationX = (w - 1920f * s) / 2f
            stage.translationY = (h - 1080f * s) / 2f
        }
    }

    // ------------------------------------------------------------------------------- clock

    private val ticker = object : Runnable {
        override fun run() {
            val now = Date()
            val c = Calendar.getInstance().apply { time = now }
            val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE); val s = c.get(Calendar.SECOND)
            pixelClock.setTime(h, m, s)
            analogClock.setTime(h, m, s)
            if (m != lastMinute) {
                lastMinute = m
                greeting.text = if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
                date.text = dateFormat.format(now)
                sky.hour = h + m / 60f
                // On the hour, the forecast row moves on by one hour.
                if (m == 0) Weather.cached(this@HomeActivity)?.let { showWeather(it) }
            }
            main.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private val periodic = object : Runnable {
        override fun run() {
            refreshNetflix()
            if (online) refreshOnline()
            main.postDelayed(this, 10 * 60_000L)
        }
    }

    // ------------------------------------------------------------------------------- dock

    private fun rounded(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        view.clipToOutline = true
    }

    private fun rect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun refreshApps() {
        worker.execute {
            val found = Apps.resolve(this)
            val art = found.map { e -> e.pkg?.let { pkg -> BubbleView.bigIcon(this, pkg)?.let { it to BubbleView.fillsCircle(it) } } }
            main.post {
                if (found == entries && bubbles.size == found.size) return@post
                val keep = entries.getOrNull(sel)?.slot?.key
                entries = found
                val kept = found.indexOfFirst { it.slot.key == keep }
                sel = if (kept >= 0) kept else found.indexOfFirst { it.slot.key == "netflix" }.coerceAtLeast(0)
                buildDock(art)
            }
        }
    }

    // ------------------------------------------------------------------------------- floating bubbles

    /** One bubble's place: x, y (centre, on the 1920 x 1080 stage), size, see-through, blur, highlight. */
    private class Bub(val view: BubbleView, val phase: Float) {
        var x = 0f; var y = 0f; var size = 0f; var alpha = 0f; var blur = 0f; var star = 0f; var z = 0f
        var fx = 0f; var fy = 0f; var fsize = 0f; var falpha = 0f; var fblur = 0f; var fstar = 0f
        var tx = 0f; var ty = 0f; var tsize = 0f; var talpha = 0f; var tblur = 0f; var tstar = 0f
        var incoming = false
        var appliedBlur = -1f
    }

    private val bubbles = mutableListOf<Bub>()
    /** Which back spot each app is in (-1 = it is the selected one, at the front). */
    private var spotOf = IntArray(0)
    private var moveStart = 0L
    private var bubbleClock = 0f

    private var halo: HaloView? = null
    private val iconColors = HashMap<String, Int>()
    private val homeListChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == RemoteLink.SELECT) {
                // The phone moved its carousel: this one moves to the same app.
                val id = i.getStringExtra("id") ?: return
                val at = entries.indexOfFirst { HomeList.idOf(it) == id }
                if (at >= 0) moveTo(at)
            } else if (i.action == RemoteLink.DPAD) {
                // The phone's arrows and OK, on our own home screen: the same as the TV remote's.
                val code = i.getIntExtra("code", 0)
                HomeKeyService.lastDpad = "home got $code focus=${hasWindowFocus()}"
                if (code != 0) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            } else refreshApps()
        }
    }

    /** The coloured shadow follows the selected app: it switches to the new app's colour and fades up. */
    private var haloKey: String? = null
    private fun updateHalo(animate: Boolean) {
        val h = halo ?: return
        val key = entries.getOrNull(sel)?.slot?.key ?: return
        val target = if (zone == Zone.DOCK) 1f else 0.45f
        h.animate().cancel()
        if (key != haloKey) {
            haloKey = key
            h.color = HaloView.knownColor(key) ?: iconColors[key] ?: HaloView.colorFor(key)   // set straight away, so nothing can skip it
            if (!animate) { h.alpha = target; return }
            h.alpha = 0.2f
            h.animate().alpha(target).setDuration(600).setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else if (animate) h.animate().alpha(target).setDuration(300).start() else h.alpha = target
    }

    private fun buildDock(art: List<Pair<Drawable, Boolean>?>) {
        dock.removeAllViews(); bubbles.clear(); haloKey = null
        halo = HaloView(this).also { h ->
            dock.addView(h, FrameLayout.LayoutParams(HALO, HALO))
            h.translationX = STAR_X - HALO / 2f; h.translationY = STAR_Y - HALO / 2f
        }
        for ((i, e) in entries.withIndex()) {
            val v = BubbleView(this)
            val a = art.getOrNull(i)
            if (a != null) { v.art = a.first; v.fullBleed = a.second }
            else HomeList.letterArt(v, e.slot.key, e.slot.label)
            // Apps added from the phone get their shadow colour from their own icon.
            if (HaloView.knownColor(e.slot.key) == null && a != null) {
                try {
                    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                    a.first.setBounds(0, 0, 48, 48); a.first.draw(android.graphics.Canvas(bmp))
                    iconColors[e.slot.key] = RemoteLink.averageColor(bmp)
                } catch (x: Exception) { }
            }
            v.pivotX = BUB / 2f; v.pivotY = BUB / 2f
            dock.addView(v, FrameLayout.LayoutParams(BUB, BUB))
            bubbles += Bub(v, i * 1.7f)
        }
        // Everyone except the selected app takes a back spot, in order.
        spotOf = IntArray(entries.size)
        var k = 0
        for (i in entries.indices) spotOf[i] = if (i == sel) -1 else k++
        for ((i, b) in bubbles.withIndex()) { target(i, b); b.x = b.tx; b.y = b.ty; b.size = b.tsize; b.alpha = b.talpha; b.blur = b.tblur; b.star = b.tstar }
        moveStart = 0L
        applyBubbles()
        updateHalo(false)
        markDock(false)
    }

    /** Where bubble i should be right now. */
    private fun target(i: Int, b: Bub) {
        val s = spotOf.getOrElse(i) { 0 }
        if (s < 0) {
            val dim = zone != Zone.DOCK
            b.tx = STAR_X; b.ty = STAR_Y; b.tsize = 340f; b.talpha = if (dim) 0.85f else 1f; b.tblur = 0f; b.tstar = if (dim) 0.3f else 1f; b.z = 1f
        } else {
            val sp = BACK[s % BACK.size]
            b.z = sp[2]
            b.tx = sp[0]; b.ty = sp[1]; b.tsize = 70f + 150f * sp[2]; b.talpha = 0.16f + 0.66f * sp[2]; b.tblur = (1f - sp[2]) * 8f + 0.5f; b.tstar = 0f
        }
    }

    /** Moves to another app: it flies forward into the front spot, the old one drifts back to where the new one was. */
    private fun moveTo(next: Int) {
        if (next == sel || next !in entries.indices || spotOf.size != entries.size) return
        val prev = sel
        sel = next
        // The phone's carousel follows.
        HomeKeyService.instance?.homeSelected(HomeList.idOf(entries[next]))
        // Every app at the back moves to a new spot, so the whole cloud of apps shifts around the one coming forward.
        val back = entries.indices.filter { it != next }
        var spots = back.indices.shuffled()
        if (back.size > 1 && back.withIndex().all { (k, i) -> spotOf[i] == spots[k] }) spots = spots.drop(1) + spots.first()
        for ((k, i) in back.withIndex()) spotOf[i] = spots[k]
        spotOf[next] = -1
        for ((i, b) in bubbles.withIndex()) {
            b.fx = b.x; b.fy = b.y; b.fsize = b.size; b.falpha = b.alpha; b.fblur = b.blur; b.fstar = b.star
            target(i, b)
            b.incoming = i == next
            // The new front bubble passes in front of everything on its way.
            b.view.translationZ = if (i == next) 2f else if (i == prev) 1f else 0f
        }
        moveStart = System.nanoTime()
        updateHalo(true)
        markDock()
    }

    private val bubbleFrame = object : android.view.Choreographer.FrameCallback {
        var last = 0L
        override fun doFrame(now: Long) {
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            bubbleClock += dt
            if (moveStart != 0L) {
                val t = ((now - moveStart) / 1e9f / MOVE_SECONDS).coerceIn(0f, 1f)
                for (b in bubbles) {
                    // The incoming bubble springs forward with a little overshoot; the rest glide.
                    val e = if (b.incoming) overshoot.getInterpolation(t) else glide.getInterpolation(t)
                    val ep = glide.getInterpolation(t)
                    b.x = b.fx + (b.tx - b.fx) * ep; b.y = b.fy + (b.ty - b.fy) * ep
                    b.size = b.fsize + (b.tsize - b.fsize) * e
                    b.alpha = b.falpha + (b.talpha - b.falpha) * ep
                    b.blur = b.fblur + (b.tblur - b.fblur) * ep
                    b.star = b.fstar + (b.tstar - b.fstar) * ep
                }
                if (t >= 1f) moveStart = 0L
            }
            applyBubbles()
            main.let { android.view.Choreographer.getInstance().postFrameCallback(this) }
        }
    }
    private val overshoot = android.view.animation.OvershootInterpolator(1.4f)
    private val glide = DecelerateInterpolator(2.2f)

    private fun applyBubbles() {
        val sweepPos = (bubbleClock % 4f) / 1.1f - 0.3f   // a sweep of light every 4 seconds
        for (b in bubbles) {
            // Everything bobs gently; the far ones a bit more, so the depth feels real.
            val bobY = kotlin.math.sin(bubbleClock * 0.7f + b.phase) * (if (b.star > 0.5f) 4f else 7f)
            val bobX = kotlin.math.cos(bubbleClock * 0.5f + b.phase) * 4f
            val v = b.view
            v.translationX = b.x - BUB / 2f + bobX
            v.translationY = b.y - BUB / 2f + bobY
            val sc = b.size / BUB
            v.scaleX = sc; v.scaleY = sc
            v.alpha = b.alpha
            v.star = b.star
            v.sweep = if (b.star > 0.5f) sweepPos else -1f
            if (b.star > 0.01f) v.invalidate()
            if (android.os.Build.VERSION.SDK_INT >= 31 && kotlin.math.abs(b.blur - b.appliedBlur) > 0.25f) {
                b.appliedBlur = b.blur
                val r = b.blur / sc.coerceAtLeast(0.1f)
                v.setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL))
            }
        }
    }

    private fun markDock(animate: Boolean = true) {
        if (moveStart == 0L) updateHalo(animate)
        // Leaving the apps (to the buttons at the top) dims the selected one a little.
        if (spotOf.size == bubbles.size && bubbles.isNotEmpty()) {
            val i = sel
            bubbles.getOrNull(i)?.let { b ->
                target(i, b)
                if (moveStart == 0L) {
                    if (animate) { b.fx = b.x; b.fy = b.y; b.fsize = b.size; b.falpha = b.alpha; b.fblur = b.blur; b.fstar = b.star; b.incoming = false
                        for ((j, o) in bubbles.withIndex()) if (j != i) { o.fx = o.x; o.fy = o.y; o.fsize = o.size; o.falpha = o.alpha; o.fblur = o.blur; o.fstar = o.star; target(j, o); o.incoming = false }
                        moveStart = System.nanoTime() }
                    else { b.alpha = b.talpha; b.star = b.tstar }
                }
            }
        }
        // The sky drifts a little with you as you move between apps.
        if (entries.isNotEmpty()) {
            val offset = (sel - (entries.size - 1) / 2f) * -14f
            sky.animate().translationX(offset).setDuration(900).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        updatePicks()
    }

    private fun openSelected() {
        val e = entries.getOrNull(sel) ?: return
        when (e.slot.kind) {
            SlotKind.APP -> {
                val launch = e.pkg?.let { Apps.launchIntent(this, it) }
                if (launch != null) safeStart(launch)
                else if (e.slot.url != null) { if (!Apps.openUrl(this, e.slot.url)) say("No browser found on the TV") }
                else if (e.slot.store != null) {
                    say("Opening the Play Store to install ${e.slot.label}")
                    safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${e.slot.store}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            SlotKind.WEB -> if (!Apps.openUrl(this, e.slot.url!!)) say("No browser found on the TV")
            SlotKind.SETTINGS -> safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SlotKind.INPUTS -> showInputs()
        }
    }

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (e: Exception) {
        Log.w("LivingRoomHome", "Could not open $i", e); false
    }

    // ------------------------------------------------------------------------------- picks (Netflix, YouTube)

    private fun refreshNetflix() {
        worker.execute {
            val p = Feeds.netflix(this)
            main.post { if (p != netflixPicks) { netflixPicks = p; updatePicks() } }
        }
    }

    private fun refreshOnline() {
        val w = Weather.cached(this)
        if (w == null || System.currentTimeMillis() - w.fetchedAt > 30 * 60_000L) {
            worker.execute { Weather.fetch(this)?.let { nw -> main.post { showWeather(nw) } } }
        }
    }

    private fun currentPicks(): List<Pick> = when (entries.getOrNull(sel)?.slot?.key) {
        else -> emptyList()
    }

    private fun updatePicks() {
        val key = entries.getOrNull(sel)?.slot?.key
        val list = currentPicks()
        val show = (zone == Zone.DOCK || zone == Zone.PICKS) && list.isNotEmpty()
        if (!show) { picks.visibility = View.GONE; shownPicks = emptyList(); shownPicksKey = null; return }
        if (list != shownPicks || key != shownPicksKey || picks.visibility != View.VISIBLE) {
            shownPicks = list
            shownPicksKey = key
            picksTitle.visibility = View.GONE
            picksTitle.text = "Latest from your channels"
            picksRow.removeAllViews()
            // Sit right above the app they belong to.
            val n = dock.childCount.coerceAtLeast(1)
            val start = (1920 - (n * 270 + (n - 1) * TILE_GAP)) / 2
            val rowW = list.size * PICK_W + (list.size - 1) * PICK_GAP
            (picks.layoutParams as FrameLayout.LayoutParams).leftMargin = (start + sel * (270 + TILE_GAP)).coerceIn(90, 1830 - rowW)
            picks.requestLayout()
            for ((i, p) in list.withIndex()) {
                val card = FrameLayout(this).apply { background = rect(Color.argb(40, 255, 255, 255), 18f) }
                rounded(card, 18f)
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                card.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val caption = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18, 44, 18, 14)
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(210, 0, 0, 0)))
                }
                caption.addView(TextView(this).apply {
                    text = p.title; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f); typeface = Typeface.DEFAULT_BOLD
                })
                caption.addView(TextView(this).apply {
                    text = p.meta; setTextColor(Color.argb(210, 255, 255, 255)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f)
                })
                card.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
                // Play button, only on the video you are on.
                val play = FrameLayout(this).apply {
                    tag = "play"; visibility = View.GONE
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(242, 255, 255, 255)) }
                }
                play.addView(ImageView(this).apply { setImageResource(R.drawable.ic_play); setColorFilter(Color.rgb(17, 17, 17)) },
                    FrameLayout.LayoutParams(26, 26, Gravity.CENTER))
                card.addView(play, FrameLayout.LayoutParams(54, 54).apply { leftMargin = 18; topMargin = 16 })
                card.pivotX = PICK_W / 2f; card.pivotY = PICK_H.toFloat()
                picksRow.addView(card, LinearLayout.LayoutParams(PICK_W, PICK_H).apply { if (i > 0) leftMargin = PICK_GAP })
                card.alpha = 0f; card.translationY = 50f
                card.animate().alpha(pickAlpha(i)).translationY(0f).setStartDelay(90L * i).setDuration(560)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
                val uri = p.pictureUri
                if (uri != null) worker.execute { Pictures.load(this, uri, online)?.let { b -> main.post { img.setImageBitmap(b) } } }
            }
            picks.visibility = View.VISIBLE
            picks.alpha = 0f; picks.translationY = 40f
            picks.animate().alpha(1f).translationY(0f).setDuration(520).setInterpolator(DecelerateInterpolator(2f)).start()
            return
        }
        markPicks()
    }

    /** On the apps: both videos slightly faded. On the videos: the one you are on is bright, the other fades. */
    private fun pickAlpha(i: Int) = if (zone != Zone.PICKS) 0.78f else if (i == pickSel) 1f else 0.45f

    private fun markPicks() {
        for (i in 0 until picksRow.childCount) {
            val v = picksRow.getChildAt(i)
            val on = zone == Zone.PICKS && i == pickSel
            val sc = if (on) 1.08f else 1f
            v.animate().scaleX(sc).scaleY(sc).alpha(pickAlpha(i)).translationY(0f).setStartDelay(0).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
            v.findViewWithTag<View>("play")?.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun openPick() {
        val e = entries.getOrNull(sel)
        val p = shownPicks.getOrNull(pickSel)
        val target = p?.open
        if (p == null || target == null) { openSelected(); return }
        if (e?.slot?.key == "youtube") { if (!Apps.openUrl(this, target, e.pkg)) openSelected(); return }
        try {
            startActivity(Intent.parseUri(target, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) { openSelected() }
    }

    private fun askListPermissionOnce() {
        val prefs = getSharedPreferences("home", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_list", false)) return
        prefs.edit().putBoolean("asked_list", true).apply()
        val missing = listOf("android.permission.READ_TV_LISTINGS", "com.android.providers.tv.permission.READ_EPG_DATA")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshNetflix()
    }

    // ------------------------------------------------------------------------------- control panel

    private fun buildPills() {
        pills = listOf(
            Pill("Search", R.drawable.ic_search) { search() },
            Pill("Wi-Fi", R.drawable.ic_wifi) { safeStart(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            Pill("Wallpaper", R.drawable.ic_wallpaper) {
                sky.live = !sky.live
                getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("live_sky", sky.live).apply()
                say(if (sky.live) "Live sky wallpaper" else "Plain dark wallpaper")
            },
            Pill("Screensaver", R.drawable.ic_wx_moon) {
                val now = Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!safeStart(now)) safeStart(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Pill("Settings", R.drawable.ic_settings) { safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        )
        pillsBar.removeAllViews()
        for ((i, p) in pills.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(22, 0, 22, 0)
            }
            row.addView(ImageView(this).apply { setImageResource(p.icon) }, LinearLayout.LayoutParams(26, 26))
            row.addView(TextView(this).apply {
                text = p.name; setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f); typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 12 })
            pillsBar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 64).apply { if (i > 0) leftMargin = 12 })
        }
        markPills()
    }

    private fun markPills() {
        if (pills.isEmpty()) return
        for (i in 0 until pillsBar.childCount) {
            val row = pillsBar.getChildAt(i) as LinearLayout
            val on = zone == Zone.PANEL && i == pillSel
            row.background = rect(if (on) Color.argb(242, 255, 255, 255) else Color.argb(31, 255, 255, 255), 32f)
            val fg = if (on) Color.rgb(17, 17, 17) else Color.WHITE
            (row.getChildAt(0) as ImageView).setColorFilter(fg)
            val label = row.getChildAt(1) as TextView
            label.setTextColor(fg)
            val isWifi = pills[i].name == "Wi-Fi"
            label.text = if (isWifi) (if (online) wifiLabel else "Offline") else pills[i].name
            label.visibility = if (on || isWifi) View.VISIBLE else View.GONE
            if (pills[i].name == "Search") row.visibility = if (online) View.VISIBLE else View.GONE
        }
    }

    private fun search() {
        val tries = listOf(Intent("android.search.action.GLOBAL_SEARCH"), Intent(RecognizerIntent.ACTION_WEB_SEARCH), Intent(Intent.ACTION_ASSIST))
        for (i in tries) if (safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
    }

    // ------------------------------------------------------------------------------- weather & network

    private fun showWeather(w: WeatherNow) {
        val code = Weather.code(w)
        sky.kind = Weather.kind(code)
        sky.rainMm = w.rainMm
        wxIcon.kind = Weather.iconKind(code, w.isDay)
        wxTemp.text = "${w.temp}°"
        wxLabel.text = Weather.label(w)
        wxLine1.text = "Sasolburg · Feels like ${w.feels}°"
        wxHighLow.text = "↑ ${w.high}° / ↓ ${w.low}°"
        val heads = Weather.headsUp(w)
        if (heads != null) {
            wxWarn.text = heads
            wxWarnIcon.kind = if (heads.startsWith("Thunder")) "storm" else "rain"
            wxWarnRow.visibility = View.VISIBLE
        } else if (w.rainMm > 0f) {
            wxWarn.text = "Rain ${"%.1f".format(w.rainMm)} mm in the last hour"
            wxWarnIcon.kind = "rain"
            wxWarnRow.visibility = View.VISIBLE
        } else wxWarnRow.visibility = View.GONE
        forecast.removeAllViews()
        for (hr in w.nextHours(6)) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(TextView(this).apply {
                text = hr.label; setTextColor(Color.argb(217, 255, 255, 255))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f)
            })
            col.addView(WeatherIconView(this).apply { kind = Weather.iconKind(hr.code, hr.isDay) },
                LinearLayout.LayoutParams(46, 46).apply { topMargin = 5 })
            col.addView(TextView(this).apply {
                text = "${hr.temp}°"; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextSize(TypedValue.COMPLEX_UNIT_PX, 23f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 5 })
            val pop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pop.addView(ImageView(this).apply { setImageResource(R.drawable.ic_drop) }, LinearLayout.LayoutParams(15, 15))
            pop.addView(TextView(this).apply {
                text = "${hr.rainChance}%"; setTextColor(Color.argb(230, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 4 })
            col.addView(pop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
            forecast.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { setOnline(ok) }
        }
        override fun onLost(network: Network) { main.post { setOnline(false) } }
    }

    private fun watchConnectivity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setOnline(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        try { cm.registerDefaultNetworkCallback(netCallback) } catch (e: Exception) { }
    }

    /** Offline: Search disappears and Wi-Fi says Offline. Clock, weather (last known) and apps keep working. */
    private fun setOnline(value: Boolean) {
        val changed = value != online
        online = value
        wifiLabel = wifiName() ?: "Wi-Fi"
        if (zone == Zone.PANEL && !value && pills.getOrNull(pillSel)?.name == "Search") pillSel = 1
        markPills()
        if (value && changed) refreshOnline()
    }

    /** The connected Wi-Fi's name. Android only gives it to apps with location permission (setup grants it). */
    private fun wifiName(): String? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------------------- inputs (HDMI)

    private fun showInputs() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val all = try { tim?.tvInputList.orEmpty() } catch (e: Exception) { emptyList() }
        val ports = all.filter { it.isPassthroughInput && it.parentId == null && !hidden(it) }
        inputs = ports.map { port ->
            val child = all.firstOrNull { it.parentId == port.id }
            val name = label(port)
            (if (child != null) "$name · ${label(child)}" else name) to port
        }
        if (inputs.isEmpty()) {
            say("No inputs found")
            safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        inputsList.removeAllViews()
        for ((name, _) in inputs) {
            inputsList.addView(TextView(this).apply {
                text = name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f)
                gravity = Gravity.CENTER_VERTICAL; setPadding(24, 0, 24, 0); setBackgroundResource(R.drawable.input_row)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 84).apply { topMargin = 6 })
        }
        inputSel = 0
        markInputs()
        setZone(Zone.INPUTS)
    }

    private fun hidden(info: TvInputInfo): Boolean = try { info.isHidden(this) } catch (e: Exception) { false }

    private fun label(info: TvInputInfo): String =
        info.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(this).toString()

    private fun markInputs() { for (i in 0 until inputsList.childCount) inputsList.getChildAt(i).isSelected = i == inputSel }

    private fun openInput() {
        val info = inputs.getOrNull(inputSel)?.second ?: return
        setZone(Zone.DOCK)
        val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
        if (!safeStart(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------------------------- remote

    private fun say(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        toast.alpha = 1f
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 1500L)
    }

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(300).withEndAction { toast.visibility = View.GONE }.start()
    }

    private fun setZone(z: Zone) {
        zone = z
        inputsPanel.visibility = if (z == Zone.INPUTS) View.VISIBLE else View.GONE
        stage.requestFocus()
        markDock()
        markPills()
    }

    private fun isOk(c: Int) = c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER ||
        c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (consumedDown.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val handled = handleDown(code, event.repeatCount > 0)
        if (handled) consumedDown.add(code)
        return handled || super.dispatchKeyEvent(event)
    }

    private fun handleDown(code: Int, repeat: Boolean): Boolean {
        val visiblePills = pills.indices.filter { pills[it].name != "Search" || online }
        when (zone) {
            Zone.DOCK -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> { if (entries.size > 1) moveTo((sel - 1 + entries.size) % entries.size); return true }
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (entries.size > 1) moveTo((sel + 1) % entries.size); return true }
                code == KeyEvent.KEYCODE_DPAD_UP -> {
                    if (picks.visibility == View.VISIBLE && shownPicks.isNotEmpty()) { pickSel = 0; setZone(Zone.PICKS) }
                    else { pillSel = visiblePills.firstOrNull() ?: 0; setZone(Zone.PANEL) }
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK -> return true
                isOk(code) -> { if (!repeat) openSelected(); return true }
                else -> {}
            }
            Zone.PICKS -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> { if (pickSel > 0) { pickSel--; markPicks() }; return true }
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pickSel < picksRow.childCount - 1) { pickSel++; markPicks() }; return true }
                code == KeyEvent.KEYCODE_DPAD_UP -> { pillSel = visiblePills.firstOrNull() ?: 0; setZone(Zone.PANEL); return true }
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK -> { setZone(Zone.DOCK); return true }
                isOk(code) -> { if (!repeat) openPick(); return true }
                else -> {}
            }
            Zone.PANEL -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val i = visiblePills.indexOf(pillSel)
                    if (i > 0) { pillSel = visiblePills[i - 1]; markPills() }
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val i = visiblePills.indexOf(pillSel)
                    if (i >= 0 && i < visiblePills.size - 1) { pillSel = visiblePills[i + 1]; markPills() }
                    return true
                }
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK -> { setZone(Zone.DOCK); return true }
                code == KeyEvent.KEYCODE_DPAD_UP -> return true
                isOk(code) -> { if (!repeat) pills.getOrNull(pillSel)?.action?.invoke(); return true }
                else -> {}
            }
            Zone.INPUTS -> when {
                code == KeyEvent.KEYCODE_DPAD_UP -> { inputSel = (inputSel - 1).coerceAtLeast(0); markInputs(); return true }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> { inputSel = (inputSel + 1).coerceAtMost(inputs.size - 1); markInputs(); return true }
                isOk(code) -> { if (!repeat) openInput(); return true }
                code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    setZone(Zone.DOCK); return true
                }
                else -> {}
            }
        }
        return false
    }
}
