package com.livingroom.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Google voice: say what you want, and the TV searches for it. */
class VoiceActivity : Activity() {
    private lateinit var s: Scale
    private lateinit var said: android.widget.TextView
    private lateinit var ring1: View
    private lateinit var ring2: View
    private var rec: SpeechRecognizer? = null
    private var sent = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Scale(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        root.addView(Glow(this).apply { color = Color.rgb(66, 133, 244); cy = 520f / 844f }, FrameLayout.LayoutParams(-1, -1))
        said = text(s, 34f, bold = true).apply { maxLines = 3 }
        root.place(s, said, 195f, 300f, 350f, 140f)
        ring2 = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(10, 255, 255, 255)) } }
        root.place(s, ring2, 195f, 520f, 246f, 246f)
        ring1 = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(20, 255, 255, 255)) } }
        root.place(s, ring1, 195f, 520f, 194f, 194f)
        val mic = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        mic.addView(ImageView(this).apply { setImageResource(R.drawable.ic_mic_dark) }, FrameLayout.LayoutParams(s.px(56), s.px(56), Gravity.CENTER))
        mic.setOnClickListener { tap(it); listen() }
        root.place(s, mic, 195f, 520f, 150f, 150f)
        root.place(s, roundButton(s, R.drawable.ic_close, 66f) { finish() }, 195f, 760f, 66f, 66f)
    }

    override fun onStart() {
        super.onStart(); Hub.start(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else finish()
    }

    private fun pulse(level: Float) {
        val k = 1f + (level.coerceIn(0f, 10f) / 10f) * 0.25f
        ring1.animate().scaleX(k).scaleY(k).setDuration(90).start()
        ring2.animate().scaleX(1f + (k - 1f) * 1.6f).scaleY(1f + (k - 1f) * 1.6f).setDuration(120).start()
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { said.text = "Google voice is not available on this phone"; return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { said.alpha = 0.6f; said.text = "…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) { pulse(rms) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { pulse(0f) }
                override fun onError(e: Int) { pulse(0f); if (!sent) { said.alpha = 0.6f; said.text = "Tap the mic and try again" } }
                override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { said.alpha = 1f; said.text = it } }
                override fun onEvent(t: Int, b: Bundle?) {}
                override fun onResults(b: Bundle?) {
                    val q = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    said.alpha = 1f; said.text = q; sent = true
                    Hub.send("search", "q" to q)
                    said.postDelayed({ finish() }, 700)
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    override fun onStop() { rec?.destroy(); rec = null; Hub.stop(); super.onStop() }
}
