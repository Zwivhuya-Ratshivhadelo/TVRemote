package com.livingroom.remote

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Apps: ticked ones are on the TV home screen. Tap to add or remove. Search finds any app on the TV. */
class AppsActivity : Activity() {
    private lateinit var s: Scale
    private lateinit var grid: LinearLayout
    private lateinit var search: EditText
    private var query = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Scale(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val back = ImageView(this).apply { setImageResource(R.drawable.ic_back); setPadding(s.px(8), s.px(8), s.px(8), s.px(8))
            setOnClickListener { tap(it); finish() } }
        root.place(s, back, 36f, 66f, 40f, 40f)
        val title = text(s, 28f, bold = true).apply { this.text = "Apps"; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        root.place(s, title, 62f + 130f, 66f, 260f, 40f)

        val box = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Color.argb(20, 255, 255, 255)); cornerRadius = s.px(22f) } }
        box.addView(SearchGlyph(this).apply { alpha = 0.7f },
            FrameLayout.LayoutParams(s.px(18), s.px(18), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = s.px(16) })
        search = EditText(this).apply {
            background = null; setHint("Search"); isSingleLine = true
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(150, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, s.px(15f))
            setPadding(s.px(44), 0, s.px(16), 0); gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    query = e?.toString()?.trim() ?: ""; fill()
                    grid.removeCallbacks(askPlay); if (query.length >= 2) grid.postDelayed(askPlay, 600)
                }
            })
            setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true }
        }
        box.addView(search, FrameLayout.LayoutParams(-1, -1))
        root.place(s, box, 195f, 128f, 342f, 44f)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, s.px(6), 0, s.px(40)) }
        scroll.addView(grid)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = s.px(160) })
        fill()
    }

    private val onHub = { fill() }
    private var askedAt = 0L
    private val askPlay = Runnable { askedAt = System.currentTimeMillis(); Hub.send("store_search", "q" to query); grid.postDelayed({ fill() }, 9000) }

    private fun fill() {
        grid.removeAllViews()
        val q = query.lowercase()
        // Each app once. The TV maker's built-in extras only show up when you search.
        val all = Hub.apps.distinctBy { it.label.trim().lowercase() }
        val onLabels = all.filter { it.on }.map { it.label.trim().lowercase() }
        // One of each: "DStv Stream" is the same app as "DStv" on the home screen.
        val apps = all.filter { a -> a.on || onLabels.none { a.label.trim().lowercase().startsWith("$it ") } }
        // Only your home screen apps; everything else is found by searching.
        if (q.isEmpty()) {
            val home = apps.filter { it.on }
            if (home.isEmpty()) note("Waiting for the TV…") else section(null, home)
            return
        }
        val local = apps.filter { it.label.lowercase().contains(q) }
        section(null, local.filter { it.on } + local.filter { !it.on })
        if (q.length < 2) return
        // Apps you don't have yet: from Google Play, tap to install on the TV.
        val f = Hub.found
        val have = Hub.apps.map { it.id }.toSet()
        if (f == null || f.first != query) {
            if (System.currentTimeMillis() - askedAt > 8500) return   // the TV didn't answer: show only what it has
            if (local.isEmpty()) note(if (Hub.tvOnline) "Searching…" else "The TV is off"); return
        }
        val play = f.second.filter { it.id !in have }
        if (play.isEmpty() && local.isEmpty()) { note("Nothing called \"$query\""); return }
        section(null, play, store = true)
    }

    /** The TV maker's built-in extras: only shown when you search for them. */
    private fun isExtra(label: String) = label.trim().lowercase() in setOf("daily key", "daily manage", "e-manual", "castplaytv", "live tv", "iqiyi", "media player", "file manager", "tv guide", "app store", "skyworth store")

    private fun header(name: String) {
        val h = text(s, 12f, bold = true, alpha = 0.5f, spacing = 0.1f).apply { text = name; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        grid.addView(h, LinearLayout.LayoutParams(-1, s.px(40)).apply { leftMargin = (s.offX + s.px(28f)).toInt(); topMargin = s.px(8) })
    }

    private fun note(t: String) {
        grid.addView(text(s, 14f, alpha = 0.5f).apply { text = t; gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-1, s.px(40)).apply { leftMargin = (s.offX + s.px(28f)).toInt() })
    }

    private fun section(name: String?, list: List<TvApp>, store: Boolean = false) {
        if (list.isEmpty()) return
        if (name != null) header(name) else grid.addView(View(this), LinearLayout.LayoutParams(-1, s.px(10)))
        val cols = 4
        for (r in list.chunked(cols)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            for (a in r) row.addView(cell(a, store), LinearLayout.LayoutParams(s.px(86), s.px(108)))
            repeat(cols - r.size) { row.addView(View(this), LinearLayout.LayoutParams(s.px(86), s.px(108))) }
            grid.addView(row)
        }
    }

    private fun cell(a: TvApp, store: Boolean = false): View {
        val st = if (store) Hub.installs[a.id] else null
        val c = FrameLayout(this).apply { clipChildren = false }
        val bub = Bubble(this).apply { icon = a.icon; color = a.color; label = a.label }
        // Bubble 64 wide, centred in the 86-wide cell; the badge sits on its top-right edge.
        c.addView(bub, FrameLayout.LayoutParams(s.px(64), s.px(64), Gravity.START or Gravity.TOP).apply { leftMargin = s.px(11); topMargin = s.px(8) })
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (a.on) Color.WHITE else Color.rgb(58, 60, 68)); setStroke(s.px(3), Color.BLACK) }
        }
        badge.addView(ImageView(this).apply { setImageResource(if (a.on) R.drawable.ic_check else R.drawable.ic_plus); if (a.on) setColorFilter(Color.BLACK) },
            FrameLayout.LayoutParams(s.px(12), s.px(12), Gravity.CENTER))
        c.addView(badge, FrameLayout.LayoutParams(s.px(26), s.px(26), Gravity.START or Gravity.TOP).apply { leftMargin = s.px(54); topMargin = s.px(4) })
        val name = text(s, 12f, alpha = if (st == "notv" || st == "fail") 0.5f else 1f).apply {
            text = when (st) { "installing" -> "Installing…"; "notv" -> "Not for TV"; "fail" -> "Didn't install"; else -> a.label } }
        c.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.START or Gravity.TOP).apply { topMargin = s.px(80); leftMargin = s.px(2); rightMargin = s.px(2) })
        c.setOnClickListener { tap(it)
            if (!store) Hub.setOnTv(a.id, !a.on)
            else if (st != "installing") { Hub.installs[a.id] = "installing"; Hub.send("install", "id" to a.id); fill() } }
        return c
    }

    override fun onStart() { super.onStart(); Hub.start(this); Hub.listen(onHub); Hub.send("apps_get"); fill() }
    override fun onStop() { Hub.unlisten(onHub); Hub.stop(); super.onStop() }
}

/** The little magnifying glass in the search box. */
class SearchGlyph(ctx: android.content.Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
    override fun onDraw(c: android.graphics.Canvas) {
        val u = width / 24f; p.strokeWidth = 2f * u
        c.drawCircle(11f * u, 11f * u, 6f * u, p)
        c.drawLine(15.5f * u, 15.5f * u, 20f * u, 20f * u, p)
    }
}
