package com.livingroom.home

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the Living Room home screen as the home screen.
 * Google TV will not let its own home screen be switched off, so this helper:
 *  - catches the remote's HOME button and opens ours,
 *  - swaps ours in whenever Google's home screen comes up (for example when the TV wakes up),
 *  - opens ours when the screen switches back on.
 * Google's home stays installed and on.
 */
class HomeKeyService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastOpen = 0L
    private var link: RemoteLink? = null
    private var updater: Updater? = null
    @Volatile private var waitingUpdate: java.io.File? = null
    private var front = ""

    /** Checks GitHub for a newer build: 1 minute after starting, then every 3 hours (and whenever the phone asks). */
    private val updateTick = object : Runnable {
        override fun run() { checkUpdate(); main.postDelayed(this, 3 * 60 * 60_000L) }
    }

    fun checkUpdate() {
        updater?.check { f -> main.post { waitingUpdate = f; installWhenIdle() } }
    }

    /** Updates only while our home screen is showing, so nothing you're watching gets interrupted. */
    private fun installWhenIdle() {
        val f = waitingUpdate ?: return
        if (front != packageName) return
        waitingUpdate = null
        updater?.install(f)
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // Google's home comes up as the TV wakes; open ours just after it.
            main.postDelayed({ openHome() }, 900)
            main.postDelayed({ openHome() }, 2500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(screenOn, filter)
        } catch (e: Exception) { Log.w(TAG, "screen-on not watched", e) }
        // The phone remote's link runs here, because this helper is always on.
        link = try { RemoteLink(this).also { it.start() } } catch (e: Exception) { Log.w(TAG, "remote link not started", e); null }
        updater = Updater(this, "tv").also { it.start() }
        main.postDelayed(updateTick, 60_000)
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOn) } catch (e: Exception) { }
        link?.stop()
        updater?.stop()
        main.removeCallbacks(updateTick)
        super.onDestroy()
    }

    fun openHome(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOpen < 1200) return true
        lastOpen = now
        return try {
            startActivity(Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the home screen", e); false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) return openHome(force = true)
        return true // the press itself: handled on release
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pk = event.packageName?.toString() ?: ""
        if (pk == PlayStore.VENDING) link?.onStoreScreen()
        if (pk in INSTALLERS && Updater.pending && System.currentTimeMillis() - Updater.pendingAt < 3 * 60_000) pressInstall()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pk.isNotEmpty()) {
            front = pk
            if (pk == packageName && waitingUpdate != null) main.postDelayed({ installWhenIdle() }, 3000)
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        event.packageName?.toString()?.let { link?.onWindow(it) }
        if (event.packageName?.toString() != GOOGLE_HOME) return
        // Only Google's main home page, not other screens it may show.
        val cls = event.className?.toString() ?: ""
        if (cls.isEmpty() || cls.contains("HomeActivity") || cls.contains("MainActivity")) openHome()
    }

    /**
     * The phone's arrows and OK. On our home screen they go straight to it; elsewhere the helper moves the TV's
     * focus to the next item in that direction (Android 12 has no way for an app to press the remote's arrows).
     */
    fun dpad(k: String) {
        val code = when (k) { "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN; "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT; else -> KeyEvent.KEYCODE_DPAD_CENTER }
        if (front == packageName) { sendBroadcast(Intent(RemoteLink.DPAD).setPackage(packageName).putExtra("code", code)); return }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val a = when (k) { "up" -> 16; "down" -> 17; "left" -> 18; "right" -> 19; else -> 20 }   // GLOBAL_ACTION_DPAD_*
            if (performGlobalAction(a)) return
        }
        val root = rootInActiveWindow ?: return
        val cur = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (k == "ok") {
            var n = cur
            while (n != null && !n.isClickable) n = n.parent
            n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        val dir = when (k) { "up" -> android.view.View.FOCUS_UP; "down" -> android.view.View.FOCUS_DOWN; "left" -> android.view.View.FOCUS_LEFT; else -> android.view.View.FOCUS_RIGHT }
        val next = cur?.focusSearch(dir) ?: firstFocusable(root)
        next?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun firstFocusable(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> firstFocusable(c)?.let { return it } }
        return null
    }

    /** Our own update is on Android's install screen: press its Install / Update button. */
    private fun pressInstall() {
        val root = rootInActiveWindow ?: return
        fun find(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            val t = (n.text ?: "").toString().trim().lowercase()
            if ((t == "update" || t == "install") && n.isEnabled) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
            return null
        }
        var b = find(root)
        while (b != null && !b.isClickable) b = b.parent
        if (b?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) Updater.pending = false
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "LivingRoomHome"
        private const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"
        private val INSTALLERS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")
    }
}
