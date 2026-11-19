package com.livingroom.remote

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * Settings: Apps, Picture, Wi-Fi, Bluetooth. Picture, Wi-Fi and Bluetooth are changed right here on the phone;
 * the TV sends what it has ("tvset") and the phone sends back what you pick.
 * One screen for all four: no "page" = the menu, otherwise page = picture / wifi / bluetooth.
 */
class SettingsActivity : Activity() {
    private lateinit var s: Scale
    private lateinit var list: LinearLayout
    private var page: String? = null
    private var joining: String? = null       // Wi-Fi network you tapped, waiting for its password
    private var dragging = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Scale(this)
        page = intent.getStringExtra("page")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        if (page == null) menu(root) else detail(root)
    }

    // ------------------------------------------------------------------------------- the menu

    private fun menu(root: FrameLayout) {
        val title = text(s, 28f, bold = true).apply { text = "Settings"; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        root.place(s, title, 24f + 130f, 72f, 260f, 40f)
        val done = text(s, 17f, bold = true).apply { text = "Done"; setTextColor(GREEN); gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setOnClickListener { tap(it); finish() } }
        root.place(s, done, 366f - 40f, 72f, 80f, 40f)
        val tiles = listOf(
            Triple("Apps", R.drawable.ic_apps, null),
            Triple("Picture", R.drawable.ic_picture, "picture"),
            Triple("Wi-Fi", R.drawable.ic_wifi, "wifi"),
            Triple("Bluetooth", R.drawable.ic_bluetooth, "bluetooth"))
        tiles.forEachIndexed { k, (name, icon, p) ->
            val tile = FrameLayout(this).apply { background = roundBg(Color.argb(18, 255, 255, 255), s.px(28f)) }
            val circle = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(26, 255, 255, 255)) } }
            circle.addView(ImageView(this).apply { setImageResource(icon) }, FrameLayout.LayoutParams(s.px(26), s.px(26), Gravity.CENTER))
            tile.addView(circle, FrameLayout.LayoutParams(s.px(56), s.px(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = s.px(26) })
            tile.addView(text(s, 20f, bold = true).apply { text = name; gravity = Gravity.START or Gravity.CENTER_VERTICAL },
                FrameLayout.LayoutParams(-1, -1).apply { leftMargin = s.px(102) })
            tile.setOnClickListener { tap(it)
                if (p == null) startActivity(Intent(this, AppsActivity::class.java))
                else startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", p)) }
            root.place(s, tile, 195f, 220f + k * 130f, 342f, 104f)
        }
    }

    // ------------------------------------------------------------------------------- Picture / Wi-Fi / Bluetooth

    private fun detail(root: FrameLayout) {
        val back = ImageView(this).apply { setImageResource(R.drawable.ic_back); setPadding(s.px(8), s.px(8), s.px(8), s.px(8))
            setOnClickListener { tap(it); finish() } }
        root.place(s, back, 36f, 66f, 40f, 40f)
        val name = when (page) { "picture" -> "Picture"; "wifi" -> "Wi-Fi"; else -> "Bluetooth" }
        val title = text(s, 28f, bold = true).apply { text = name; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        root.place(s, title, 62f + 130f, 66f, 260f, 40f)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((s.offX + s.px(24f)).toInt(), 0, (s.offX + s.px(24f)).toInt(), s.px(40)) }
        scroll.addView(list)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = s.px(96) })
        fill()
    }

    private val onHub = { if (page != null && !dragging) fill() }

    private fun fill() {
        list.removeAllViews()
        val t = Hub.tvSet
        val part = when (page) { "picture" -> t?.optJSONObject("picture"); "wifi" -> t?.optJSONObject("wifi"); else -> t?.optJSONObject("bt") }
        if (part == null) { note(if (Hub.tvOnline || !Hub.linked) "Waiting for the TV" else "The TV is off"); return }
        when (page) { "picture" -> picture(part); "wifi" -> wifi(part); else -> bluetooth(part) }
    }

    private fun picture(p: JSONObject) {
        val modes = p.optJSONArray("modes")
        if (modes != null && modes.length() > 0) {
            label("PICTURE MODE")
            val cur = p.optString("mode")
            for (i in 0 until modes.length()) {
                val m = modes.optString(i)
                row(m, right = if (m == cur) tick() else null) { Hub.send("picture_mode", "mode" to m); setLocal("picture", "mode", m) }
            }
        }
        if (p.has("backlight")) {
            label("BACKLIGHT")
            val bar = Slider(this).apply { frac = p.optInt("backlight") / 100f
                onMove = { dragging = true }
                onDone = { f -> dragging = false; val v = (f * 100).toInt(); Hub.send("backlight", "v" to v); setLocal("picture", "backlight", v) } }
            list.addView(bar, LinearLayout.LayoutParams(-1, s.px(34)).apply { topMargin = s.px(12) })
        }
    }

    private fun wifi(w: JSONObject) {
        val ssid = w.optString("ssid")
        if (ssid.isNotEmpty()) {
            label("CONNECTED")
            row(ssid, sub = w.optString("signal").takeIf { it.isNotEmpty() }, right = tick())
        }
        val nets = w.optJSONArray("nets")
        if (nets != null && nets.length() > 0) {
            label("OTHER NETWORKS")
            for (i in 0 until nets.length()) {
                val n = nets.optJSONObject(i) ?: continue
                val name = n.optString("ssid"); if (name.isEmpty() || name == ssid) continue
                val locked = n.optBoolean("lock")
                row(name, right = if (locked) lock() else null) {
                    if (locked) { joining = if (joining == name) null else name; fill() } else Hub.send("wifi_join", "ssid" to name)
                }
                if (joining == name) password(name)
            }
        }
    }

    private fun password(name: String) {
        val box = EditText(this).apply {
            setHint("Password for $name"); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(120, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, s.px(16f))
            background = roundBg(Color.argb(20, 255, 255, 255), s.px(20f)); setPadding(s.px(18), 0, s.px(18), 0)
        }
        list.addView(box, LinearLayout.LayoutParams(-1, s.px(56)).apply { topMargin = s.px(10) })
        val join = text(s, 16f, bold = true).apply { text = "Join"; setTextColor(Color.BLACK); background = roundBg(Color.WHITE, s.px(26f))
            setOnClickListener { tap(it); val pw = box.text.toString(); if (pw.isEmpty()) return@setOnClickListener
                Hub.send("wifi_join", "ssid" to name, "pass" to pw); joining = null; fill() } }
        list.addView(join, LinearLayout.LayoutParams(-1, s.px(52)).apply { topMargin = s.px(10) })
        box.requestFocus()
    }

    private fun bluetooth(b: JSONObject) {
        val on = b.optBoolean("on")
        row("Bluetooth", right = Toggle(this).apply { this.on = on }) {
            Hub.send("bt_power", "on" to !on); setLocal("bt", "on", !on)
        }
        if (!on) return
        val devs = b.optJSONArray("devices") ?: return
        val connected = (0 until devs.length()).mapNotNull { devs.optJSONObject(it) }.filter { it.optBoolean("conn") }
        val paired = (0 until devs.length()).mapNotNull { devs.optJSONObject(it) }.filter { !it.optBoolean("conn") }
        if (connected.isNotEmpty()) { label("CONNECTED"); for (d in connected) row(d.optString("name"), sub = d.optString("kind").takeIf { it.isNotEmpty() }, right = tick()) }
        if (paired.isNotEmpty()) {
            label("PAIRED")
            for (d in paired) row(d.optString("name"), right = text(s, 14f, alpha = 0.6f).apply { text = "Connect" }) {
                Hub.send("bt_connect", "addr" to d.optString("addr"))
            }
        }
    }

    /** Shows your change straight away; the TV confirms when it sends its settings again. */
    private fun setLocal(part: String, key: String, v: Any) {
        val o = Hub.tvSet?.optJSONObject(part) ?: return
        o.put(key, v); fill()
    }

    // ------------------------------------------------------------------------------- pieces

    private fun label(t: String) {
        list.addView(text(s, 13f, alpha = 0.55f, spacing = 0.08f).apply { text = t; gravity = Gravity.START or Gravity.BOTTOM },
            LinearLayout.LayoutParams(-1, s.px(40)))
    }

    private fun note(t: String) {
        list.addView(text(s, 15f, alpha = 0.5f).apply { text = t; gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-1, s.px(60)))
    }

    private fun row(title: String, sub: String? = null, right: View? = null, onTap: (() -> Unit)? = null) {
        val r = FrameLayout(this).apply { background = roundBg(Color.argb(16, 255, 255, 255), s.px(20f)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(text(s, 17f, bold = true).apply { text = title; gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2))
        if (sub != null) col.addView(text(s, 12f, alpha = 0.55f).apply { text = sub; gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2))
        r.addView(col, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = s.px(18); rightMargin = s.px(90) })
        if (right != null) r.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = s.px(18) })
        if (onTap != null) r.setOnClickListener { tap(it); onTap() }
        list.addView(r, LinearLayout.LayoutParams(-1, s.px(62)).apply { topMargin = s.px(10) })
    }

    private fun tick() = ImageView(this).apply { setImageResource(R.drawable.ic_check); setColorFilter(GREEN) }

    private fun lock() = LockGlyph(this).apply { minimumWidth = s.px(16); minimumHeight = s.px(16) }

    override fun onStart() { super.onStart(); Hub.start(this); Hub.listen(onHub); if (page != null) { Hub.send("tvset_get"); fill() } }
    override fun onStop() { Hub.unlisten(onHub); Hub.stop(); super.onStop() }

    companion object { val GREEN = Color.rgb(30, 215, 96) }

    // ------------------------------------------------------------------------------- small views

    /** Backlight bar: drag along it. */
    class Slider(ctx: android.content.Context) : View(ctx) {
        var frac = 0f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        var onMove: () -> Unit = {}
        var onDone: (Float) -> Unit = {}
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val r = h / 2
            p.color = Color.argb(26, 255, 255, 255); c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
            p.color = Color.WHITE; c.drawRoundRect(0f, 0f, maxOf(h, width * frac), h, r, r, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            frac = e.x / width
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onMove() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDone(frac)
            }
            return true
        }
    }

    /** On/off switch. */
    class Toggle(ctx: android.content.Context) : View(ctx) {
        var on = false; set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) { val d = resources.displayMetrics.density; setMeasuredDimension((52 * d).toInt(), (30 * d).toInt()) }
        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val r = h / 2
            p.color = if (on) GREEN else Color.argb(46, 255, 255, 255); c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
            p.color = Color.WHITE; val k = h * 0.4f
            c.drawCircle(if (on) width - r else r, r, k, p)
        }
    }

    /** Small padlock for networks with a password. */
    class LockGlyph(ctx: android.content.Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onMeasure(w: Int, h: Int) { val d = resources.displayMetrics.density; setMeasuredDimension((16 * d).toInt(), (16 * d).toInt()) }
        override fun onDraw(c: Canvas) {
            val u = width / 24f; p.strokeWidth = 2f * u
            c.drawRoundRect(5f * u, 11f * u, 19f * u, 20f * u, 2f * u, 2f * u, p)
            c.drawArc(8f * u, 4f * u, 16f * u, 12f * u, 180f, 180f, false, p)
            c.drawLine(8f * u, 8f * u, 8f * u, 11f * u, p); c.drawLine(16f * u, 8f * u, 16f * u, 11f * u, p)
        }
    }
}
