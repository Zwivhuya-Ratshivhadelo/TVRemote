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
        val box = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(90, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_PX, s.px(22f))
            background = GradientDrawable().apply { setColor(Color.argb(23, 255, 255, 255)); cornerRadius = s.px(32f) }
            setPadding(s.px(22), 0, s.px(22), 0); gravity = Gravity.CENTER_VERTICAL; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        root.place(s, box, 195f, 130f, 340f, 64f)
        fun send() {
            val t = box.text.toString(); if (t.isBlank()) return
            Hub.send("type", "text" to t); finish()
        }
        box.setOnEditorActionListener { v, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { tap(v); send(); true } else false }
        val btn = text(s, 18f, bold = true).apply {
            text = "Send to TV"; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = s.px(30f) }
            setOnClickListener { tap(it); send() }
        }
        root.place(s, btn, 195f, 200f, 340f, 60f)
        box.requestFocus()
        box.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT) }, 200)
    }
    override fun onStart() { super.onStart(); Hub.start(this) }
    override fun onStop() { Hub.stop(); super.onStop() }
}
