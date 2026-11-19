package com.livingroom.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Works the TV's own menus for the phone, the way you would with the remote: opens a menu, finds an item by its
 * name, presses it, types into a box. Used for what Android won't let an app change directly (joining a Wi-Fi
 * network, connecting a Bluetooth device, the picture settings). Every step gives up after a few seconds, and the
 * home screen comes back at the end either way.
 */
class MenuDriver(private val svc: HomeKeyService) {

    /** One step: returns true once it has done its job on the screen that is showing. */
    class Step(val name: String, val wait: Long = 7000, val run: (AccessibilityNodeInfo) -> Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val steps = ArrayDeque<Step>()
    private var stepAt = 0L
    private var done: ((Boolean) -> Unit)? = null
    private var scrolls = 0

    val busy: Boolean get() = steps.isNotEmpty()

    fun run(open: Intent?, list: List<Step>, finished: (Boolean) -> Unit) {
        if (busy) { finished(false); return }
        steps.clear(); steps.addAll(list); done = finished; scrolls = 0
        stepAt = SystemClock.uptimeMillis()
        if (open != null) try { svc.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { finish(false); return }
        main.postDelayed(tick, 700)
    }

    private val tick = object : Runnable {
        override fun run() {
            val step = steps.firstOrNull() ?: return
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null }
            val ok = root != null && try { step.run(root) } catch (e: Exception) { Log.w(TAG, "step ${step.name}", e); false }
            val now = SystemClock.uptimeMillis()
            when {
                ok -> { steps.removeFirst(); stepAt = now; scrolls = 0; if (steps.isEmpty()) { finish(true); return } }
                now - stepAt > step.wait -> { Log.w(TAG, "gave up at ${step.name}"); finish(false); return }
            }
            main.postDelayed(this, if (ok) 900 else 400)
        }
    }

    private fun finish(ok: Boolean) {
        steps.clear(); main.removeCallbacks(tick)
        val d = done; done = null
        main.postDelayed({ svc.openHome(force = true); d?.invoke(ok) }, if (ok) 1200 else 300)
    }

    // ------------------------------------------------------------------------------- what steps use

    companion object {
        private const val TAG = "LivingRoomMenu"

        fun all(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo> = ArrayList()): MutableList<AccessibilityNodeInfo> {
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { all(it, out) }
            return out
        }

        fun label(n: AccessibilityNodeInfo): String = ((n.text ?: n.contentDescription) ?: "").toString().trim()

        /** The item called [text] (exactly, ignoring case), or one whose name contains it if [loose]. */
        fun find(root: AccessibilityNodeInfo, text: String, loose: Boolean = false): AccessibilityNodeInfo? {
            val t = text.trim().lowercase()
            val nodes = all(root).filter { it.isVisibleToUser }
            return nodes.firstOrNull { label(it).lowercase() == t } ?: if (loose) nodes.firstOrNull { label(it).lowercase().contains(t) } else null
        }

        fun press(n: AccessibilityNodeInfo?): Boolean {
            var c = n
            while (c != null && !c.isClickable) c = c.parent
            return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        /** Finds and presses [text]; scrolls the list down a bit at a time while it isn't on screen yet. */
        fun pressNamed(root: AccessibilityNodeInfo, text: String, loose: Boolean = false): Boolean {
            find(root, text, loose)?.let { return press(it) }
            all(root).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            return false
        }

        /** Types into the box on screen (the focused one, or the first one). */
        fun type(root: AccessibilityNodeInfo, text: String): Boolean {
            val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: all(root).firstOrNull { it.isEditable } ?: return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            if (android.os.Build.VERSION.SDK_INT >= 30) box.performAction(android.R.id.accessibilityActionImeEnter)
            return true
        }

        fun texts(root: AccessibilityNodeInfo): List<String> = all(root).filter { it.isVisibleToUser }.map { label(it) }.filter { it.isNotEmpty() }

        /** Just waits [ms] before the next step. */
        fun pause(ms: Long): Step { var at = 0L; return Step("pause", ms + 5000) { if (at == 0L) at = SystemClock.uptimeMillis(); SystemClock.uptimeMillis() - at >= ms } }
    }
}
