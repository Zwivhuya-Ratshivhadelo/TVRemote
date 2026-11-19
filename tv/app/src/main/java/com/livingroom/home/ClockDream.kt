package com.livingroom.home

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Screensaver: black screen with a dim clock that moves to a new spot every minute,
 * so nothing burns into the screen. No photos and no ads.
 */
class ClockDream : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: View
    private lateinit var time: TextView
    private lateinit var date: TextView
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.UK)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.UK)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            time.text = timeFormat.format(now)
            date.text = dateFormat.format(now)
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private val move = object : Runnable {
        override fun run() {
            val root = box.parent as View
            val maxX = (root.width - box.width).coerceAtLeast(0)
            val maxY = (root.height - box.height).coerceAtLeast(0)
            box.animate().alpha(0f).setDuration(800).withEndAction {
                box.x = Random.nextInt(maxX + 1).toFloat()
                box.y = Random.nextInt(maxY + 1).toFloat()
                box.animate().alpha(1f).setDuration(800).start()
            }.start()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        setContentView(R.layout.dream_clock)
        box = findViewById(R.id.dreamBox)
        time = findViewById(R.id.dreamTime)
        date = findViewById(R.id.dreamDate)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.post(tick)
        handler.postDelayed(move, 1000L)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
