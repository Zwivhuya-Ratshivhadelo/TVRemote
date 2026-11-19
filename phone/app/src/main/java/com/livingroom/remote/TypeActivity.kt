package com.livingroom.remote

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/** Type on the phone; it goes into the box on the TV. */
class TypeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = Scale(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val back = android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_back); setPadding(s.px(8), s.px(8), s.px(8), s.px(8))
            setOnClickListener { tap(it); finish() } }
        root.place(s, back, 36f, 66f, 40f, 40f)
        val title = text(s, 28f, bold = true).apply { text = "Type"; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        root.place(s, title, 62f + 130f, 66f, 260f, 40f)
        val box = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(90, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, s.px(20f))
            background = GradientDrawable().apply { setColor(Color.argb(20, 255, 255, 255)); cornerRadius = s.px(30f) }
            setPadding(s.px(22), 0, s.px(66), 0); gravity = Gravity.CENTER_VERTICAL; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        root.place(s, box, 195f, 150f, 342f, 60f)
        fun send() {
            val t = box.text.toString(); if (t.isBlank()) return
            Hub.send("type", "text" to t); finish()
        }
        box.setOnEditorActionListener { v, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { tap(v); send(); true } else false }
        root.place(s, roundButton(s, R.drawable.ic_send, 46f, iconSize = 20f) { send() }, 366f - 27f, 150f, 46f, 46f)
        box.requestFocus()
        box.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT) }, 200)
    }
    override fun onStart() { super.onStart(); Hub.start(this) }
    override fun onStop() { Hub.stop(); super.onStop() }
}
