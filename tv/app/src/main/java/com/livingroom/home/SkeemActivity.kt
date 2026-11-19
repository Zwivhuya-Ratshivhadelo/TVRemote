package com.livingroom.home

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/** The 19:30 card: "Skeem Saam is starting", opens it after 10 seconds unless you skip. */
class SkeemActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var left = 10
    private lateinit var count: TextView
    private lateinit var bar: ProgressBar
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            count.text = "Opening in $left seconds"
            bar.progress = (10 - left) * 10
            if (left <= 0) { watch(); return }
            left--
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skeem)
        count = findViewById(R.id.skeemCount)
        bar = findViewById(R.id.skeemBar)
        val watch = findViewById<Button>(R.id.skeemWatch)
        val skip = findViewById<Button>(R.id.skeemSkip)
        watch.setOnClickListener { watch() }
        skip.setOnClickListener { skip() }
        watch.requestFocus()
        handler.post(tick)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { skip(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun watch() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        Skeem.open(this)
        finish()
    }

    private fun skip() {
        done = true
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
